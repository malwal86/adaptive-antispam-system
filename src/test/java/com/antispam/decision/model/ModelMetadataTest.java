package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * π_train must travel with the model, not be a magic constant (story 04.04 AC 5): the
 * served artifact's training base rate is read from the sidecar exported alongside it
 * (see {@code ml/train_classifier.py}), keyed by {@code model_version}. The bootstrap
 * model is trained on 1500 ham + 1500 spam + 1500 phish, so P(abuse) = 3000/4500 = ⅔.
 */
class ModelMetadataTest {

    @Test
    void reads_the_training_base_rate_of_the_served_model() {
        ModelMetadata metadata = new ModelMetadata();

        assertThat(metadata.trainingBaseRate(OnnxModel.MODEL_VERSION))
                .isCloseTo(0.6666666666666666, within(1e-9));
    }

    @Test
    void fails_loudly_for_a_model_version_with_no_metadata() {
        ModelMetadata metadata = new ModelMetadata();

        assertThatThrownBy(() -> metadata.trainingBaseRate("does-not-exist-v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist-v9");
    }
}
