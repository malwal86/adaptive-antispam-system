package com.antispam.experiment.replay.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.replay.ReplayDecision;
import com.antispam.experiment.replay.ReplayDecisionRepository;
import com.antispam.experiment.replay.ReplayRun;
import com.antispam.experiment.replay.ReplayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the replay endpoints, standalone (no Spring context, no broker, no DB) so
 * it runs everywhere. It pins the client-facing outcomes: triggering a replay returns 202 with the
 * run summary, an unknown policy is a 400 (not a 500), and the decisions endpoint serializes the
 * recorded verdicts.
 */
class ReplayControllerTest {

    private ReplayService replayService;
    private ReplayDecisionRepository decisions;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        replayService = Mockito.mock(ReplayService.class);
        decisions = Mockito.mock(ReplayDecisionRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new ReplayController(replayService, decisions))
                .setControllerAdvice(new ReplayExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void triggering_a_replay_returns_202_with_the_run_summary() throws Exception {
        UUID runId = UUID.randomUUID();
        when(replayService.startReplay("cand-v2")).thenReturn(new ReplayRun(runId, "cand-v2", 42));

        mockMvc.perform(post("/replays")
                        .contentType("application/json")
                        .content("{\"policyVersion\":\"cand-v2\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.policyVersion").value("cand-v2"))
                .andExpect(jsonPath("$.publishedCount").value(42));
    }

    @Test
    void rejects_an_unknown_policy_with_400() throws Exception {
        when(replayService.startReplay(any()))
                .thenThrow(new IllegalArgumentException("no policy with version ghost"));

        mockMvc.perform(post("/replays")
                        .contentType("application/json")
                        .content("{\"policyVersion\":\"ghost\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no policy with version ghost"));
    }

    @Test
    void lists_a_runs_decisions_as_json() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID emailId = UUID.randomUUID();
        ScoredDecision scored = new ScoredDecision(
                Decision.QUARANTINE, List.of(), RouteUsed.MODEL, List.of(), "cand-v2", 0.85);
        when(decisions.findByRunId(runId)).thenReturn(
                List.of(new ReplayDecision(UUID.randomUUID(), runId, emailId, scored, Instant.EPOCH)));

        mockMvc.perform(get("/replays/{runId}/decisions", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].emailId").value(emailId.toString()))
                .andExpect(jsonPath("$[0].decision").value("QUARANTINE"))
                .andExpect(jsonPath("$[0].route").value("MODEL"))
                .andExpect(jsonPath("$[0].posterior").value(0.85))
                .andExpect(jsonPath("$[0].policyVersion").value("cand-v2"));
    }
}
