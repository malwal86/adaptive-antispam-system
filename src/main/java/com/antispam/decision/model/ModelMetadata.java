package com.antispam.decision.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Surfaces the training metadata exported alongside an ONNX model artifact — today,
 * just the training base rate {@code π_train} that log-odds fusion needs (story 04.04).
 *
 * <p><b>Why this exists.</b> A calibrated content model already encodes the base rate
 * of abuse in its training data; fusion subtracts {@code logit(π_train)} once so the
 * sender prior is not double-counted (PRD §Subsystem 2). That base rate is a property
 * of <em>how the model was trained</em>, so it is read from the sidecar the training
 * script writes next to the {@code .onnx} ({@code spam-classifier-<version>.metadata.json}),
 * keyed by {@code model_version} — not hard-coded. When the retrain loop (Epic 10)
 * promotes a model trained on a different class balance, its base rate travels with it
 * and fusion stays correct without a code change.
 *
 * <p>Sidecars are fetched through the {@link ModelArtifactStore} on first request and cached per
 * version. Going through the store rather than the classpath directly is what keeps fusion correct
 * after a promotion (story 10.04): a promoted candidate's sidecar lives only in remote storage, so a
 * classpath-only read would miss it and throw on <em>every</em> decision once the candidate is served.
 * The store resolves the bundled bootstrap sidecar from the classpath and a promoted candidate's from
 * remote storage, transparently. A missing or malformed sidecar is a packaging defect, not a runtime
 * condition, so it fails loudly rather than silently substituting a default that would quietly bias
 * every fused score.
 */
@Component
public class ModelMetadata {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Double> baseRateByVersion = new ConcurrentHashMap<>();
    private final ModelArtifactStore artifactStore;

    @Autowired
    public ModelMetadata(ModelArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * The training base rate {@code π_train} for {@code modelVersion}: the fraction of
     * its training corpus that was abuse (spam or phish), in {@code (0,1)}.
     *
     * @throws IllegalStateException if no sidecar exists for the version or it is malformed
     */
    public double trainingBaseRate(String modelVersion) {
        return baseRateByVersion.computeIfAbsent(modelVersion, this::loadBaseRate);
    }

    private double loadBaseRate(String modelVersion) {
        byte[] sidecar;
        try {
            sidecar = artifactStore.metadataBytes(modelVersion);
        } catch (ModelArtifactNotFoundException e) {
            throw new IllegalStateException(
                    "no training metadata for model version " + modelVersion, e);
        }
        try {
            TrainingMetadata metadata = mapper.readValue(sidecar, TrainingMetadata.class);
            double baseRate = metadata.trainingBaseRate();
            if (baseRate <= 0.0 || baseRate >= 1.0 || Double.isNaN(baseRate)) {
                throw new IllegalStateException("trainingBaseRate for model version " + modelVersion
                        + " must be in (0,1) but was " + baseRate);
            }
            return baseRate;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to parse training metadata for model version " + modelVersion, e);
        }
    }

    /** The subset of the sidecar fusion needs; unknown fields are ignored on read. */
    private record TrainingMetadata(String modelVersion, double trainingBaseRate) {
    }
}
