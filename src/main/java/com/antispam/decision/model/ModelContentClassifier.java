package com.antispam.decision.model;

import com.antispam.decision.ContentClassifier;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.ModelScores;
import com.antispam.decision.RouteUsed;
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
 * <p><b>Scope.</b> This story delivers the <em>scores</em>, not a score-driven
 * verdict. Turning calibrated, reputation-fused scores into a tier is Epics
 * 04.02/04.04/04.05; until then the model path returns a provisional
 * {@link Decision#ALLOW} carrying the recorded {@link ModelScores}. The score, not
 * the tier, is the observable deliverable here.
 */
@Component
public class ModelContentClassifier implements ContentClassifier {

    private final EmailFeatureExtractor extractor;
    private final OnnxModel model;

    @Autowired
    public ModelContentClassifier(EmailFeatureExtractor extractor, OnnxModel model) {
        this.extractor = extractor;
        this.model = model;
    }

    @Override
    public DecisionOutcome classify(Email email) {
        long startNanos = System.nanoTime();
        EmailFeatures features = extractor.extract(email);
        float[] vector = ModelFeatureVector.toVector(features.features());
        ModelScores scores = model.score(vector);
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // Provisional verdict: the tier is owned by later stories (fusion 04.04,
        // policy 04.05). What this story commits to is the recorded score.
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, latencyMs, scores);
    }
}
