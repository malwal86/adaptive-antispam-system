package com.antispam.decision.model;

import com.antispam.decision.ContentClassifier;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.ModelScores;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.calibration.ActiveCalibrator;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.features.EmailFeatures;
import com.antispam.ingest.Email;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The model path of the decision pipeline (story 04.01): for mail no hard rule
 * overrode, it extracts features, scores them with the in-process ONNX classifier,
 * and records the raw spam/phishing scores on the outcome. This replaces the
 * Phase-0 {@code PlaceholderContentClassifier} — the {@link ContentClassifier} seam
 * existed precisely so Epic 04 could swap a real model in here.
 *
 * <p><b>Features are extracted here, not read from storage.</b> Feature rows are
 * written asynchronously off the Kafka spine (Epic 02) and may not exist yet when
 * the synchronous analyze path reaches this point. Extraction is deterministic and
 * cheap, so the classifier recomputes the {@link EmailFeatures} for the email at
 * the current {@link EmailFeatureExtractor#FEATURE_VERSION} — guaranteeing the
 * vector matches the model's feature version without a read-after-write race.
 *
 * <p><b>Calibration (story 04.02).</b> The raw model probability is not a trustworthy
 * frequency, so this path passes its {@link ModelScores#rawMalicious() raw abuse score}
 * through the {@link ActiveCalibrator} and records the result as the served
 * {@link ModelScores#calibratedConfidence() calibrated confidence}. Until a calibration
 * is fit the active calibrator is the identity, so the served confidence equals the raw
 * score; once fit, it is the corrected probability the fusion stage (04.04) consumes.
 *
 * <p><b>Scope.</b> This story delivers the <em>scores</em>, not a score-driven
 * verdict. Turning calibrated, reputation-fused scores into a tier is Epics
 * 04.04/04.05; until then the model path returns a provisional
 * {@link Decision#ALLOW} carrying the recorded {@link ModelScores}. The score, not
 * the tier, is the observable deliverable here.
 */
@Component
public class ModelContentClassifier implements ContentClassifier {

    private final EmailFeatureExtractor extractor;
    private final OnnxModel model;
    private final ActiveCalibrator calibrator;

    @Autowired
    public ModelContentClassifier(EmailFeatureExtractor extractor, OnnxModel model,
            ActiveCalibrator calibrator) {
        this.extractor = extractor;
        this.model = model;
        this.calibrator = calibrator;
    }

    @Override
    public DecisionOutcome classify(Email email) {
        long startNanos = System.nanoTime();
        EmailFeatures features = extractor.extract(email);
        float[] vector = ModelFeatureVector.toVector(features.features());
        ModelScores rawScores = model.score(vector);
        ModelScores scores = rawScores.withCalibratedConfidence(
                calibrator.calibrate(rawScores.rawMalicious()));
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // Provisional verdict: the tier is owned by later stories (fusion 04.04,
        // policy 04.05). What this story commits to is the recorded score.
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, latencyMs, scores);
    }
}
