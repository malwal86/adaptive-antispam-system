package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.antispam.decision.ModelScores;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Served model follows the active policy (story 10.04 AC 2/3): the model version it scores with is the
 * active policy's, read live, so a promotion (flag flips to a new version) is served on the next
 * decision and a rollback (flag flips back) reverts on the next decision — no redeploy, no per-email
 * swap. With no active policy it falls back to the bootstrap version. The registry is mocked so this
 * pins the version-resolution wiring without loading a real session.
 */
@ExtendWith(MockitoExtension.class)
class ServedModelTest {

    private static final float[] VECTOR = new float[ModelFeatureVector.FEATURE_COUNT];

    @Mock
    private ModelRegistry registry;

    @Mock
    private PolicyRepository policies;

    private ServedModel servedModel() {
        return new ServedModel(registry, policies);
    }

    private static Policy policyFor(String modelVersion) {
        return new Policy("policy-" + modelVersion, true, 0.5, 0.8, 0.95, 0.4, 0.05, 5,
                modelVersion, Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static ModelScores scoresFor(String modelVersion) {
        return new ModelScores(0.1, 0.1, modelVersion);
    }

    @Test
    void scores_with_the_active_policys_model_version() {
        when(policies.findActive()).thenReturn(Optional.of(policyFor("model-7")));
        when(registry.score("model-7", VECTOR)).thenReturn(scoresFor("model-7"));

        assertThat(servedModel().score(VECTOR).modelVersion()).isEqualTo("model-7");
    }

    @Test
    void serves_the_new_version_after_a_promotion_flips_the_active_policy() {
        // First decision under the old model; then the active flag flips to the promoted candidate.
        when(policies.findActive())
                .thenReturn(Optional.of(policyFor("model-old")))
                .thenReturn(Optional.of(policyFor("model-new")));
        when(registry.score("model-old", VECTOR)).thenReturn(scoresFor("model-old"));
        when(registry.score("model-new", VECTOR)).thenReturn(scoresFor("model-new"));

        ServedModel servedModel = servedModel();

        assertThat(servedModel.score(VECTOR).modelVersion()).isEqualTo("model-old");
        assertThat(servedModel.score(VECTOR).modelVersion()).isEqualTo("model-new");
    }

    @Test
    void reverts_to_the_prior_version_after_a_rollback_flips_the_flag_back() {
        when(policies.findActive())
                .thenReturn(Optional.of(policyFor("model-new")))
                .thenReturn(Optional.of(policyFor("model-old")));
        when(registry.score("model-new", VECTOR)).thenReturn(scoresFor("model-new"));
        when(registry.score("model-old", VECTOR)).thenReturn(scoresFor("model-old"));

        ServedModel servedModel = servedModel();

        assertThat(servedModel.score(VECTOR).modelVersion()).isEqualTo("model-new");
        assertThat(servedModel.score(VECTOR).modelVersion()).isEqualTo("model-old");
    }

    @Test
    void falls_back_to_the_bootstrap_version_when_no_policy_is_active() {
        when(policies.findActive()).thenReturn(Optional.empty());
        when(registry.score(OnnxModel.MODEL_VERSION, VECTOR))
                .thenReturn(scoresFor(OnnxModel.MODEL_VERSION));

        assertThat(servedModel().activeModelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
        assertThat(servedModel().score(VECTOR).modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
    }
}
