package com.antispam.experiment.replay.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.experiment.replay.ComparisonReport;
import com.antispam.experiment.replay.LabeledReplayDecision;
import com.antispam.experiment.replay.PolicyMetrics;
import com.antispam.experiment.replay.ReplayAbRun;
import com.antispam.experiment.replay.ReplayAbService;
import com.antispam.seed.GroundTruthLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the A/B endpoints, standalone (no context, broker, or DB). It pins the
 * client-facing outcomes: starting an A/B returns 202 with both run ids, an unknown policy is a 400,
 * the comparison serializes per-policy metrics plus {@code B − A} deltas, and a not-yet-landed run
 * is a 409 (poll), not a 500.
 */
class ReplayAbControllerTest {

    private ReplayAbService abService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        abService = Mockito.mock(ReplayAbService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new ReplayAbController(abService))
                .setControllerAdvice(new ReplayAbExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void starting_an_ab_returns_202_with_both_run_ids() throws Exception {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        when(abService.startAb("base-v1", "cand-v2"))
                .thenReturn(new ReplayAbRun(runA, "base-v1", runB, "cand-v2", 42));

        mockMvc.perform(post("/replays/ab")
                        .contentType("application/json")
                        .content("{\"policyVersionA\":\"base-v1\",\"policyVersionB\":\"cand-v2\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runIdA").value(runA.toString()))
                .andExpect(jsonPath("$.policyVersionA").value("base-v1"))
                .andExpect(jsonPath("$.runIdB").value(runB.toString()))
                .andExpect(jsonPath("$.policyVersionB").value("cand-v2"))
                .andExpect(jsonPath("$.publishedCount").value(42));
    }

    @Test
    void rejects_an_unknown_policy_with_400() throws Exception {
        when(abService.startAb(any(), any()))
                .thenThrow(new IllegalArgumentException("no policy with version ghost"));

        mockMvc.perform(post("/replays/ab")
                        .contentType("application/json")
                        .content("{\"policyVersionA\":\"ghost\",\"policyVersionB\":\"cand-v2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no policy with version ghost"));
    }

    @Test
    void compare_serializes_per_policy_metrics_and_deltas() throws Exception {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        PolicyMetrics a = PolicyMetrics.of("base-v1", "m6", List.of(
                labeled(Decision.ALLOW, RouteUsed.MODEL, "base-v1", GroundTruthLabel.SPAM)));
        PolicyMetrics b = PolicyMetrics.of("cand-v2", "m7", List.of(
                labeled(Decision.BLOCK, RouteUsed.MODEL, "cand-v2", GroundTruthLabel.SPAM)));
        when(abService.compare(runA, runB)).thenReturn(ComparisonReport.of(a, b));

        mockMvc.perform(get("/replays/ab/compare")
                        .param("runA", runA.toString())
                        .param("runB", runB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyA.policyVersion").value("base-v1"))
                .andExpect(jsonPath("$.policyA.modelVersion").value("m6"))
                .andExpect(jsonPath("$.policyA.recall").value(0.0))
                .andExpect(jsonPath("$.policyB.policyVersion").value("cand-v2"))
                .andExpect(jsonPath("$.policyB.recall").value(1.0))
                .andExpect(jsonPath("$.deltas.recall").value(1.0));
    }

    @Test
    void comparing_a_not_yet_landed_run_is_a_409() throws Exception {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        when(abService.compare(runA, runB))
                .thenThrow(new IllegalStateException("no labeled decisions for replay run " + runA));

        mockMvc.perform(get("/replays/ab/compare")
                        .param("runA", runA.toString())
                        .param("runB", runB.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no labeled decisions for replay run " + runA));
    }

    private static LabeledReplayDecision labeled(
            Decision decision, RouteUsed route, String policyVersion, GroundTruthLabel label) {
        return new LabeledReplayDecision(UUID.randomUUID(), decision, route, policyVersion, label);
    }
}
