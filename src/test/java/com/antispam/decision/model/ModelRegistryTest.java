package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.decision.ModelScores;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * The registry's lazy-load and caching contract (story 10.04). A version is loaded from the artifact
 * store on first score and reused thereafter — the cache is the "no per-email model hot-swap" guarantee
 * (AC 4), pinned here by asserting the store is consulted exactly once no matter how often a version is
 * scored. The bootstrap version is served from the seeded session without ever touching the store.
 *
 * <p>Uses a real {@link OnnxModel} (cheap, deterministic) for the bootstrap seed and a stub store that
 * hands back the bootstrap ONNX bytes under fake candidate names, so a "promoted" version builds a real,
 * scoreable session without any remote storage.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelRegistryTest {

    private OnnxModel bootstrap;
    private byte[] candidateOnnxBytes;
    private float[] vector;

    @BeforeAll
    void setUp() {
        bootstrap = new OnnxModel();
        // Real, loadable ONNX bytes to stand in for a "promoted candidate" the store would serve.
        candidateOnnxBytes = new ClasspathModelArtifactStore().modelBytes(OnnxModel.MODEL_VERSION);
        vector = new float[ModelFeatureVector.FEATURE_COUNT];
        Arrays.fill(vector, 1.0f);
    }

    @AfterAll
    void close() throws Exception {
        bootstrap.close();
    }

    private ModelArtifactStore storeServing(String version) {
        ModelArtifactStore store = Mockito.mock(ModelArtifactStore.class);
        when(store.modelBytes(version)).thenReturn(candidateOnnxBytes);
        return store;
    }

    @Test
    void serves_the_bootstrap_version_without_consulting_the_store() {
        ModelArtifactStore store = Mockito.mock(ModelArtifactStore.class);
        ModelRegistry registry = new ModelRegistry(bootstrap, store);
        try {
            ModelScores scores = registry.score(OnnxModel.MODEL_VERSION, vector);

            assertThat(scores.modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
            assertThat(registry.loadedVersions()).contains(OnnxModel.MODEL_VERSION);
            verifyNoInteractions(store);
        } finally {
            registry.close();
        }
    }

    @Test
    void lazy_loads_a_version_from_the_store_on_first_score() {
        ModelArtifactStore store = storeServing("candidate-a");
        ModelRegistry registry = new ModelRegistry(bootstrap, store);
        try {
            assertThat(registry.loadedVersions()).doesNotContain("candidate-a");

            ModelScores scores = registry.score("candidate-a", vector);

            assertThat(scores.modelVersion()).isEqualTo("candidate-a");
            assertThat(registry.loadedVersions()).contains("candidate-a");
            verify(store).modelBytes("candidate-a");
        } finally {
            registry.close();
        }
    }

    @Test
    void serves_a_cached_session_without_reloading_when_scored_again() {
        // AC 4: repeated scoring of the same version must not reload — no per-email hot-swap.
        ModelArtifactStore store = storeServing("candidate-b");
        ModelRegistry registry = new ModelRegistry(bootstrap, store);
        try {
            registry.score("candidate-b", vector);
            registry.score("candidate-b", vector);
            registry.score("candidate-b", vector);

            verify(store, times(1)).modelBytes("candidate-b");
        } finally {
            registry.close();
        }
    }

    @Test
    void loads_a_second_version_when_a_different_one_is_requested() {
        ModelArtifactStore store = Mockito.mock(ModelArtifactStore.class);
        when(store.modelBytes("candidate-c")).thenReturn(candidateOnnxBytes);
        when(store.modelBytes("candidate-d")).thenReturn(candidateOnnxBytes);
        ModelRegistry registry = new ModelRegistry(bootstrap, store);
        try {
            registry.score("candidate-c", vector);
            registry.score("candidate-d", vector);

            assertThat(registry.loadedVersions()).contains("candidate-c", "candidate-d");
            verify(store).modelBytes("candidate-c");
            verify(store).modelBytes("candidate-d");
        } finally {
            registry.close();
        }
    }
}
