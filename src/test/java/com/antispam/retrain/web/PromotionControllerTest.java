package com.antispam.retrain.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.decision.model.ModelArtifactNotFoundException;
import com.antispam.decision.model.ModelRegistry;
import com.antispam.decision.model.OnnxModel;
import com.antispam.decision.model.ServedModel;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.retrain.ModelPromotionService;
import com.antispam.retrain.ModelVersionRepository;
import com.antispam.retrain.PromotionResult;
import com.antispam.retrain.RollbackResult;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The promotion endpoints' web contract (story 10.04): the happy paths return the result shape, and the
 * service's refusals map to honest statuses — gate-fail → 409, missing artifact → 404, unknown rollback
 * target → 400 — via {@link PromotionExceptionHandler}. The active-model read surfaces what is served
 * (AC 5). Standalone MockMvc, no Spring context, mirroring {@code PrecisionGateControllerTest}.
 */
class PromotionControllerTest {

    private static final UUID RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ModelPromotionService promotionService;
    private ServedModel servedModel;
    private PolicyRepository policies;
    private ModelRegistry registry;
    private ModelVersionRepository modelVersions;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        promotionService = Mockito.mock(ModelPromotionService.class);
        servedModel = Mockito.mock(ServedModel.class);
        policies = Mockito.mock(PolicyRepository.class);
        registry = Mockito.mock(ModelRegistry.class);
        modelVersions = Mockito.mock(ModelVersionRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PromotionController(
                        promotionService, servedModel, policies, registry, modelVersions))
                .setControllerAdvice(new PromotionExceptionHandler())
                .build();
    }

    @Test
    void promote_returns_the_new_active_model() throws Exception {
        when(promotionService.promote(eq(RUN), any())).thenReturn(new PromotionResult(
                "model-2", "policy-2", "policy-1", 0.97,
                Instant.parse("2026-06-24T10:00:00Z"), "alice"));

        mockMvc.perform(post("/retrain/promote").param("run", RUN.toString()).param("by", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("model-2"))
                .andExpect(jsonPath("$.activePolicyVersion").value("policy-2"))
                .andExpect(jsonPath("$.priorPolicyVersion").value("policy-1"));
    }

    @Test
    void promote_returns_409_when_the_candidate_failed_the_gate() throws Exception {
        when(promotionService.promote(eq(RUN), any()))
                .thenThrow(new IllegalStateException("did not pass the precision gate"));

        mockMvc.perform(post("/retrain/promote").param("run", RUN.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("did not pass the precision gate"));
    }

    @Test
    void promote_returns_404_when_the_artifact_was_never_staged() throws Exception {
        when(promotionService.promote(eq(RUN), any()))
                .thenThrow(new ModelArtifactNotFoundException("not staged"));

        mockMvc.perform(post("/retrain/promote").param("run", RUN.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rollback_returns_the_restored_model() throws Exception {
        when(promotionService.rollback(eq("policy-1"), any())).thenReturn(new RollbackResult(
                "policy-1", "model-1", "policy-2", Instant.parse("2026-06-24T11:00:00Z"), "bob"));

        mockMvc.perform(post("/retrain/rollback").param("to", "policy-1").param("by", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePolicyVersion").value("policy-1"))
                .andExpect(jsonPath("$.modelVersion").value("model-1"));
    }

    @Test
    void rollback_returns_400_for_an_unknown_policy() throws Exception {
        when(promotionService.rollback(eq("ghost"), any()))
                .thenThrow(new IllegalArgumentException("no policy to roll back to with version ghost"));

        mockMvc.perform(post("/retrain/rollback").param("to", "ghost"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void active_model_reports_what_is_served() throws Exception {
        when(servedModel.activeModelVersion()).thenReturn("model-2");
        when(policies.findActive()).thenReturn(Optional.of(new Policy(
                "policy-2", true, 0.5, 0.8, 0.95, 0.4, 0.05, 5, "model-2",
                Instant.parse("2026-06-01T00:00:00Z"))));
        when(registry.loadedVersions()).thenReturn(Set.of(OnnxModel.MODEL_VERSION, "model-2"));
        when(modelVersions.findLatest()).thenReturn(Optional.empty());

        mockMvc.perform(get("/retrain/active-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servedModelVersion").value("model-2"))
                .andExpect(jsonPath("$.activePolicyVersion").value("policy-2"))
                .andExpect(jsonPath("$.loadedVersions").isArray());
    }
}
