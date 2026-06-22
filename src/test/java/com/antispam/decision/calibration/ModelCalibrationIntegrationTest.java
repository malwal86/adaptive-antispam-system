package com.antispam.decision.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.calibration.CalibrationReportRepository.StoredReport;
import com.antispam.decision.calibration.ModelCalibrationService.CalibrationRun;
import com.antispam.decision.model.OnnxModel;
import com.antispam.eval.BootstrapEvalSplitService;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Calibration end-to-end against a real Postgres (story 04.02, AC 3 + AC 5): a labeled,
 * split-assigned corpus is scored through the real ONNX model, a calibrator is fit on the
 * {@code train} side and its reliability measured on the held-out {@code eval} side, and the
 * resulting report is persisted per {@code model_version} and retrievable as evidence. The
 * gate ceiling is relaxed to 1.0 here so the run deterministically passes and installs —
 * the gate's rejection behaviour is pinned as a pure unit in {@link CalibrationGateTest}.
 */
@TestPropertySource(properties = {
        "antispam.calibration.min-samples-per-side=1",
        "antispam.calibration.max-ece=1.0",
        "antispam.calibration.bins=5",
})
class ModelCalibrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String DOMAIN_SUFFIX = ".calibration.test";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private BootstrapEvalSplitService splitService;

    @Autowired
    private ModelCalibrationService calibrationService;

    @Autowired
    private CalibrationReportRepository reports;

    @Autowired
    private ActiveCalibrator activeCalibrator;

    @Test
    void fits_on_train_measures_on_eval_and_persists_retrievable_evidence() {
        // A labeled corpus spread across time so both split sides are populated: calm ham
        // and shouty/phishy abuse, each family its own domain and year.
        for (int i = 0; i < 8; i++) {
            ingestLabeled("ham" + i, 2001 + i, GroundTruthLabel.HAM, hamBody());
            ingestLabeled("bad" + i, 2001 + i, GroundTruthLabel.SPAM, spamBody());
        }
        splitService.rebuild();

        CalibrationRun run = calibrationService.calibrate();

        // The run produced honest, in-range evidence tagged with the served model.
        StoredReport stored = run.stored();
        assertThat(stored.report().modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
        assertThat(stored.report().method()).isEqualTo("isotonic");
        assertThat(stored.report().sampleCount()).isGreaterThanOrEqualTo(1);
        assertThat(stored.report().eceRaw()).isBetween(0.0, 1.0);
        assertThat(stored.report().eceCalibrated()).isBetween(0.0, 1.0);
        assertThat(stored.report().calibratedBins()).hasSize(5);
        // The curve accounts for every held-out prediction.
        assertThat(stored.report().calibratedBins().stream().mapToLong(ReliabilityBin::count).sum())
                .isEqualTo(stored.report().sampleCount());

        // The ceiling was relaxed, so it passed the gate and is now serving.
        assertThat(run.installed()).isTrue();
        assertThat(activeCalibrator.isCalibrated()).isTrue();

        // The evidence is durable and retrievable as "the current calibration".
        assertThat(reports.findLatest(OnnxModel.MODEL_VERSION)).isPresent();
        assertThat(calibrationService.currentReport())
                .get()
                .satisfies(latest -> {
                    assertThat(latest.passed()).isTrue();
                    assertThat(latest.report().sampleCount()).isEqualTo(stored.report().sampleCount());
                });
    }

    private void ingestLabeled(String domain, int year, GroundTruthLabel label, String body) {
        String raw = "From: sender@" + domain + DOMAIN_SUFFIX + "\n"
                + "Date: Tue, 1 Jan " + year + " 00:00:00 +0000\n"
                + "Subject: calibration " + domain + " " + year + "\n"
                + "Authentication-Results: mx.test; spf=pass\n\n"
                + body + " " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, "t-calibration-" + domain);
    }

    private static String hamBody() {
        return "Hi, confirming our lunch meeting tomorrow at noon. See you then.";
    }

    private static String spamBody() {
        return "WIN A FREE PRIZE NOW!!! CLICK http://192.168.1.9/claim to CLAIM your REWARD!!!";
    }
}
