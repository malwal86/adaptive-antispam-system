package com.antispam.feedback.gate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Decision;
import com.antispam.feedback.FeedbackAction;
import com.antispam.feedback.FeedbackEvent;
import com.antispam.feedback.FeedbackEventRepository;
import com.antispam.feedback.Persona;
import com.antispam.feedback.PersonaDefinition;
import com.antispam.feedback.PersonaRepository;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code POST /feedback/gate/{runId}} endpoint (story 07.03): it runs a recorded run's feedback
 * through the gate and reports what reached each sink. The suite shares one Postgres, so the run is
 * built from this test's own emails/personas and the response is asserted by its shape.
 */
@AutoConfigureMockMvc
class FeedbackGateApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngestService ingestService;
    @Autowired
    private PersonaRepository personas;
    @Autowired
    private FeedbackEventRepository feedbackEvents;

    @Test
    void gate_runs_and_reports_the_two_sink_fan_out() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String sender = "api-campaign-" + suffix + "@spammer.test";
        UUID runId = UUID.randomUUID();
        List<FeedbackEvent> events = new ArrayList<>();
        for (String name : List.of("api-r-a-" + suffix, "api-r-b-" + suffix, "api-r-c-" + suffix)) {
            Persona reporter = new PersonaDefinition(name, 0.5, 0.5, 0.5, false).toPersona();
            personas.seed(List.of(reporter));
            UUID emailId = ingest(sender, "claim your free reward today");
            events.add(new FeedbackEvent(UUID.randomUUID(), emailId, reporter.id(), runId,
                    FeedbackAction.REPORT, 0.8, 10L, Decision.ALLOW, GroundTruthLabel.SPAM, reporter.name()));
        }
        feedbackEvents.saveAll(events);

        mockMvc.perform(post("/feedback/gate/{runId}", runId).contentType("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.groupsTrusted").value(1))
                .andExpect(jsonPath("$.reputationEventsEmitted").value(1))
                .andExpect(jsonPath("$.retrainLabelsEmitted").value(3));
    }

    private UUID ingest(String sender, String body) {
        String tag = UUID.randomUUID().toString();
        String raw = "From: " + sender + "\r\nSubject: gate-api [" + tag + "]\r\n\r\n" + body + " ref:" + tag;
        return ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "gate-api").emailId();
    }
}
