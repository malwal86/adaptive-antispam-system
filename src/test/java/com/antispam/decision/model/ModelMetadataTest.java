package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * π_train must travel with the model, not be a magic constant (story 04.04 AC 5): the
 * served artifact's training base rate is read from the sidecar exported alongside it
 * (see {@code ml/train_classifier.py}), keyed by {@code model_version}. The bootstrap
 * model is trained on 1500 ham + 1500 spam + 1500 phish, so P(abuse) = 3000/4500 = ⅔.
 *
 * <p>Story 10.04 routes that read through the {@link ModelArtifactStore} so a promoted candidate
 * whose sidecar lives only in remote storage still resolves — guarded here with a stub store so the
 * fusion-after-promotion path does not depend on the classpath alone.
 */
class ModelMetadataTest {

    /** A store that serves only the bootstrap sidecar from the classpath; everything else is absent. */
    private static ModelMetadata bootstrapOnly() {
        return new ModelMetadata(new ClasspathModelArtifactStore());
    }

    @Test
    void reads_the_training_base_rate_of_the_served_model() {
        assertThat(bootstrapOnly().trainingBaseRate(OnnxModel.MODEL_VERSION))
                .isCloseTo(0.6666666666666666, within(1e-9));
    }

    @Test
    void fails_loudly_for_a_model_version_with_no_metadata() {
        assertThatThrownBy(() -> bootstrapOnly().trainingBaseRate("does-not-exist-v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist-v9");
    }

    @Test
    void resolves_a_promoted_candidates_base_rate_from_a_non_classpath_store() {
        // The candidate sidecar exists only in the (stubbed remote) store, never on the classpath —
        // exactly the shape after a promotion. Fusion must still find π_train for it.
        ModelArtifactStore remoteOnly = new ModelArtifactStore() {
            @Override
            public byte[] modelBytes(String modelVersion) {
                throw new ModelArtifactNotFoundException("not used here: " + modelVersion);
            }

            @Override
            public byte[] metadataBytes(String modelVersion) {
                if (modelVersion.equals("candidate-2026-06-v1")) {
                    return "{\"modelVersion\":\"candidate-2026-06-v1\",\"trainingBaseRate\":0.4}"
                            .getBytes(StandardCharsets.UTF_8);
                }
                throw new ModelArtifactNotFoundException("no metadata for " + modelVersion);
            }
        };

        ModelMetadata metadata = new ModelMetadata(remoteOnly);

        assertThat(metadata.trainingBaseRate("candidate-2026-06-v1")).isCloseTo(0.4, within(1e-9));
    }
}
