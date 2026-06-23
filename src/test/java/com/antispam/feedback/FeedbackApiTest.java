package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.RouteUsed;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code POST /feedback/simulate} endpoint (story 07.02): it runs a population over the decided
 * corpus and reports the run. The suite shares one Postgres, so we assert the run's <em>shape</em>
 * (a run id, a positive event count) and the invariant that holds over any corpus — every persisted
 * action is legal for the verdict it was shown — rather than exact counts.
 */
@AutoConfigureMockMvc
class FeedbackApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private IngestService ingestService;
    @Autowired
    private ClassificationRepository classifications;
    @Autowired
    private GroundTruthLabelRepository groundTruth;
    @Autowired
    private PersonaRepository personas;
    @Autowired
    private FeedbackEventRepository events;

    private void decidedEmail(String body, Decision decision, GroundTruthLabel label) {
        String tag = UUID.randomUUID().toString();
        String raw = "Subject: feedback-api [" + tag + "]\n\n" + body + " ref:" + tag;
        UUID emailId = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "feedback-api").emailId();
        classifications.save(emailId, new DecisionOutcome(decision, List.of(), RouteUsed.MODEL, 1L),
                null, null, null);
        groundTruth.saveIfAbsent(emailId, label, "feedback-api");
    }

    @Test
    void simulate_runs_and_every_persisted_action_is_legal_for_its_verdict() throws Exception {
        String name = "api-fb-" + UUID.randomUUID();
        personas.seed(List.of(new PersonaDefinition(name, 0.6, 0.6, 0.6, false).toPersona()));
        decidedEmail("welcome to the service please confirm", Decision.QUARANTINE, GroundTruthLabel.HAM);

        String body = """
                {"seed": 3, "size": 10, "weights": {"%s": 1}, "limit": 10000}
                """.formatted(name);

        String response = mockMvc.perform(post("/feedback/simulate")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.eventCount", Matchers.greaterThanOrEqualTo(1)))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        UUID runId = UUID.fromString(json.get("runId").asText());
        List<FeedbackEvent> written = events.findByRunId(runId);
        assertThat(written).isNotEmpty();
        assertThat(written).allSatisfy(event ->
                assertThat(event.action()).isIn(FeedbackAction.spaceFor(event.decisionShown())));
        assertThat(written).hasSize(json.get("eventCount").asInt());
    }

    @Test
    void rejects_a_run_referencing_an_unknown_persona() throws Exception {
        String body = """
                {"seed": 1, "size": 10, "weights": {"ghost-%s": 1}, "limit": 10}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/feedback/simulate").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Matchers.containsString("unknown persona")));
    }
}
