package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The classpath store reads the artifacts the jar carries (story 10.04): it must find the bootstrap
 * model and its sidecar, and report a missing version as a {@link ModelArtifactNotFoundException} (not
 * a null) so the composite can fall through to remote storage.
 */
class ClasspathModelArtifactStoreTest {

    private final ClasspathModelArtifactStore store = new ClasspathModelArtifactStore();

    @Test
    void reads_the_bootstrap_model_bytes() {
        byte[] bytes = store.modelBytes(OnnxModel.MODEL_VERSION);

        assertThat(bytes).isNotEmpty();
    }

    @Test
    void reads_the_bootstrap_metadata_sidecar() {
        byte[] bytes = store.metadataBytes(OnnxModel.MODEL_VERSION);

        // The sidecar is the JSON carrying trainingBaseRate; assert it is the right document.
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("trainingBaseRate");
    }

    @Test
    void throws_not_found_for_a_model_version_not_in_the_jar() {
        assertThatThrownBy(() -> store.modelBytes("candidate-not-bundled-v9"))
                .isInstanceOf(ModelArtifactNotFoundException.class)
                .hasMessageContaining("candidate-not-bundled-v9");
    }

    @Test
    void throws_not_found_for_metadata_of_a_version_not_in_the_jar() {
        assertThatThrownBy(() -> store.metadataBytes("candidate-not-bundled-v9"))
                .isInstanceOf(ModelArtifactNotFoundException.class)
                .hasMessageContaining("candidate-not-bundled-v9");
    }
}
