package com.antispam.decision.calibration;

import com.antispam.decision.ModelScores;
import com.antispam.decision.calibration.CalibrationCorpusRepository.LabeledSplitRow;
import com.antispam.decision.calibration.CalibrationEvaluator.Prediction;
import com.antispam.decision.calibration.CalibrationReportRepository.StoredReport;
import com.antispam.decision.model.ModelFeatureVector;
import com.antispam.decision.model.OnnxModel;
import com.antispam.eval.SplitSide;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Fits and evaluates the served model's probability calibration (story 04.02). It scores
 * the labeled corpus through the model, fits an isotonic calibrator on the split's
 * {@code train} side, and measures the resulting reliability on the held-out {@code eval}
 * side — so the number reported is honest, not memorised. The run records a
 * {@link ReliabilityReport} as evidence, judges it against the gate, and, only if it
 * passes, installs the calibrator on the serving path. The same fit-measure-gate shape the
 * retrain loop (Epic 10) will run before promoting a candidate.
 *
 * <p>Fitting uses the raw, <em>pre-calibration</em> model score (so it does not depend on
 * whatever calibrator is currently active) — calibration must be measured against the
 * model, not against itself.
 */
@Service
public class ModelCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(ModelCalibrationService.class);

    /**
     * The result of a calibration run: the persisted evidence, the gate verdict, and
     * whether the calibrator was installed on the serving path.
     *
     * @param stored    the persisted report and its gate outcome
     * @param reason    the gate's human-readable justification
     * @param installed whether the fitted calibrator is now serving
     */
    public record CalibrationRun(StoredReport stored, String reason, boolean installed) {
    }

    private final CalibrationCorpusRepository corpus;
    private final EmailRepository emails;
    private final EmailFeatureExtractor extractor;
    private final OnnxModel model;
    private final CalibrationReportRepository reports;
    private final ActiveCalibrator activeCalibrator;
    private final CalibrationProperties properties;

    @Autowired
    public ModelCalibrationService(
            CalibrationCorpusRepository corpus,
            EmailRepository emails,
            EmailFeatureExtractor extractor,
            OnnxModel model,
            CalibrationReportRepository reports,
            ActiveCalibrator activeCalibrator,
            CalibrationProperties properties) {
        this.corpus = corpus;
        this.emails = emails;
        this.extractor = extractor;
        this.model = model;
        this.reports = reports;
        this.activeCalibrator = activeCalibrator;
        this.properties = properties;
    }

    /**
     * Runs a full calibration: fit on train, measure on eval, persist, gate, and install
     * on pass.
     *
     * @return the run's evidence and outcome
     * @throws IllegalStateException if either split side has fewer than the configured
     *                               minimum samples (the measurement would be too noisy)
     */
    public CalibrationRun calibrate() {
        List<LabeledScore> trainScores = new ArrayList<>();
        List<Prediction> rawEval = new ArrayList<>();

        scoreCorpus(trainScores, rawEval);
        requireEnoughSamples(trainScores.size(), rawEval.size());

        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(trainScores);

        // Measure raw vs calibrated reliability on the held-out eval side. rawEval already
        // holds (rawScore, positive); calibrate each raw score for the calibrated curve.
        List<Prediction> calibratedEval = rawEval.stream()
                .map(p -> new Prediction(calibrator.calibrate(p.predicted()), p.actual()))
                .toList();

        int bins = properties.bins();
        ReliabilityReport report = new ReliabilityReport(
                OnnxModel.MODEL_VERSION,
                "isotonic",
                rawEval.size(),
                bins,
                CalibrationEvaluator.expectedCalibrationError(rawEval, bins),
                CalibrationEvaluator.expectedCalibrationError(calibratedEval, bins),
                CalibrationEvaluator.reliabilityCurve(calibratedEval, bins));

        CalibrationGate.Verdict verdict = CalibrationGate.evaluate(report, properties.maxEce());
        StoredReport stored = reports.save(report, properties.maxEce(), verdict.passed());

        if (verdict.passed()) {
            activeCalibrator.install(calibrator);
        }
        log.info("calibration run for model={}: eceRaw={} eceCalibrated={} passed={} installed={}",
                report.modelVersion(), report.eceRaw(), report.eceCalibrated(),
                verdict.passed(), verdict.passed());
        return new CalibrationRun(stored, verdict.reason(), verdict.passed());
    }

    /** The latest persisted calibration evidence for the served model, if any. */
    public Optional<StoredReport> currentReport() {
        return reports.findLatest(OnnxModel.MODEL_VERSION);
    }

    /**
     * Scores every split-assigned email through the model, sorting the raw abuse scores
     * into the train fit-set and the eval measurement-set. Emails with no stored row
     * (e.g. deleted) are skipped.
     */
    private void scoreCorpus(List<LabeledScore> trainScores, List<Prediction> rawEval) {
        List<LabeledSplitRow> rows = corpus.loadLabeledSplit();
        Map<UUID, Email> byId = emails.findByIds(rows.stream().map(LabeledSplitRow::emailId).toList())
                .stream().collect(Collectors.toMap(Email::id, Function.identity()));

        for (LabeledSplitRow row : rows) {
            Email email = byId.get(row.emailId());
            if (email == null) {
                continue;
            }
            double rawMalicious = scoreRawMalicious(email);
            if (row.side() == SplitSide.TRAIN) {
                trainScores.add(new LabeledScore(rawMalicious, row.positive()));
            } else {
                rawEval.add(new Prediction(rawMalicious, row.positive()));
            }
        }
    }

    /** The model's raw, uncalibrated P(abuse) for one email. */
    private double scoreRawMalicious(Email email) {
        float[] vector = ModelFeatureVector.toVector(extractor.extract(email).features());
        ModelScores raw = model.score(vector);
        return raw.rawMalicious();
    }

    private void requireEnoughSamples(int trainCount, int evalCount) {
        int min = properties.minSamplesPerSide();
        if (trainCount < min || evalCount < min) {
            throw new IllegalStateException(String.format(
                    "not enough labeled, split-assigned samples to calibrate: train=%d eval=%d, "
                            + "need >= %d per side. Seed the corpus and rebuild the eval split first.",
                    trainCount, evalCount, min));
        }
    }
}
