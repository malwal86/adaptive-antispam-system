package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.calibration.ActiveCalibrator;
import com.antispam.decision.calibration.ModelCalibrationService;
import com.antispam.decision.model.ModelMetadata;
import com.antispam.decision.model.OnnxModel;
import com.antispam.event.SenderKey;
import com.antispam.eval.BootstrapEvalSplitService;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.reputation.AuthGate;
import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.ReputationService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Fusion end-to-end against a real Postgres and the real ONNX model (story 04.04): once a
 * calibration is installed, a model-route decision fuses the calibrated content score with
 * the sender's reputation prior and persists the posterior on the {@code classifications}
 * row. The persisted posterior is checked against {@link LogOddsFusion} recomputed from the
 * same live inputs, and against the naive (no-{@code π_train}) combination — the guard that
 * fails if the base-rate subtraction is ever removed (success metric).
 *
 * <p>The calibration ceiling is relaxed to 1.0 so the run deterministically installs a
 * calibrator (its rejection behaviour is pinned as a unit in {@code CalibrationGateTest}).
 */
@TestPropertySource(properties = {
        "antispam.calibration.min-samples-per-side=1",
        "antispam.calibration.max-ece=1.0",
        "antispam.calibration.bins=5",
})
class FusionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String DOMAIN_SUFFIX = ".fusion.test";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private BootstrapEvalSplitService splitService;

    @Autowired
    private ModelCalibrationService calibrationService;

    @Autowired
    private ActiveCalibrator activeCalibrator;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private ReputationService reputationService;

    @Autowired
    private ModelMetadata modelMetadata;

    @Test
    void records_the_reputation_fused_posterior_on_a_model_route_decision() {
        // A labeled corpus across time so calibration can fit on train and measure on eval.
        for (int i = 0; i < 8; i++) {
            ingestLabeled("ham" + i, 2001 + i, GroundTruthLabel.HAM, hamBody());
            ingestLabeled("bad" + i, 2001 + i, GroundTruthLabel.SPAM, spamBody());
        }
        splitService.rebuild();
        calibrationService.calibrate();
        assertThat(activeCalibrator.isCalibrated()).isTrue();

        // A calm ham from a brand-new sender: no hard rule fires (model route), and an
        // unseen sender carries the prior's maximum Beta variance — the widest band.
        Email email = ingest("freshsender" + DOMAIN_SUFFIX, hamBody());

        Classification decided = decisionService.decide(email);

        // The model ran and its score was fused: the posterior is recorded.
        assertThat(decided.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(decided.scores()).isNotNull();
        assertThat(decided.fused()).isNotNull();
        FusedScore fused = decided.fused();
        assertThat(fused.posterior()).isBetween(0.0, 1.0);
        // A brand-new sender is maximally uncertain, so the routing band is strictly positive.
        assertThat(fused.uncertaintyBand()).isGreaterThan(0.0);

        // Recompute the expected posterior from the same live inputs the pipeline used.
        boolean dmarcAligned = AuthGate.dmarcAligned(
                EmailFeatureExtractor.authFeatures(email.metadata().authResults()));
        BetaReputation rep = reputationService.reputationFor(
                SenderKey.of(email.metadata().sender(), email.metadata().senderDomain()), dmarcAligned);
        double piTrain = modelMetadata.trainingBaseRate(OnnxModel.MODEL_VERSION);
        double calibratedConfidence = decided.scores().calibratedConfidence();
        FusedScore expected = LogOddsFusion.fuse(
                1.0 - rep.mean(), rep.variance(), calibratedConfidence, piTrain);

        assertThat(fused.posterior()).isCloseTo(expected.posterior(), within(1e-9));

        // Guard the −logit(π_train) correction in logit space, where it is a constant
        // shift independent of the operating point (in probability space the same shift
        // vanishes in the saturated tail a confident verdict sits in). The pipeline's
        // posterior logit must equal the naive sum minus a non-trivial logit(π_train);
        // drop the subtraction and this fails, because logit(⅔) ≈ 0.69, not 0.
        double naiveLogit =
                LogOddsFusion.logit(1.0 - rep.mean()) + LogOddsFusion.logit(calibratedConfidence);
        assertThat(LogOddsFusion.logit(piTrain)).isGreaterThan(0.5);
        assertThat(fused.posteriorLogit())
                .isCloseTo(naiveLogit - LogOddsFusion.logit(piTrain), within(1e-9));
    }

    private Email ingest(String fromDomain, String body) {
        String raw = "From: sender@" + fromDomain + "\n"
                + "Date: Tue, 1 Jan 2026 12:00:00 +0000\n"
                + "Subject: hello there\n"
                + "Authentication-Results: mx.test; spf=pass; dkim=pass; dmarc=pass\n\n"
                + body + " " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    private void ingestLabeled(String domain, int year, GroundTruthLabel label, String body) {
        String raw = "From: sender@" + domain + DOMAIN_SUFFIX + "\n"
                + "Date: Tue, 1 Jan " + year + " 00:00:00 +0000\n"
                + "Subject: calibration " + domain + " " + year + "\n"
                + "Authentication-Results: mx.test; spf=pass\n\n"
                + body + " " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, "t-fusion-" + domain);
    }

    private static String hamBody() {
        return "Hi, confirming our lunch meeting tomorrow at noon. See you then.";
    }

    private static String spamBody() {
        return "WIN A FREE PRIZE NOW!!! CLICK http://192.168.1.9/claim to CLAIM your REWARD!!!";
    }
}
