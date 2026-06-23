package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.ClassificationRepository;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The feedback simulator end-to-end against real Postgres (story 07.02): a population runs over a
 * decided/labeled stream and writes {@code feedback_events}, with every action legal for the verdict
 * shown (AC 1/2), the conditioning recorded (AC 4), and the run reproducible from its seed (AC 5).
 *
 * <p>The suite shares one Postgres, so the run is built from an explicit stream of this test's own
 * emails and every assertion is scoped by {@code run_id} — never to global counts. The decision-stream
 * loader is checked separately, asserting it <em>contains</em> this test's emails.
 */
class FeedbackSimulationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;
    @Autowired
    private ClassificationRepository classifications;
    @Autowired
    private GroundTruthLabelRepository groundTruth;
    @Autowired
    private PersonaRepository personas;
    @Autowired
    private FeedbackSimulationService service;
    @Autowired
    private FeedbackEventRepository events;
    @Autowired
    private DecidedEmailRepository decidedEmails;

    private UUID decidedEmail(String body, Decision decision, GroundTruthLabel label) {
        String tag = UUID.randomUUID().toString();
        String raw = "Subject: feedback [" + tag + "]\n\n" + body + " ref:" + tag;
        UUID emailId = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "feedback-it").emailId();
        classifications.save(emailId, new DecisionOutcome(decision, List.of(), RouteUsed.MODEL, 1L),
                null, null, null);
        groundTruth.saveIfAbsent(emailId, label, "feedback-it");
        return emailId;
    }

    @Test
    void runs_a_population_over_a_stream_and_records_legal_conditioned_events() {
        String suffix = UUID.randomUUID().toString();
        personas.seed(List.of(
                new PersonaDefinition("it-reporter-" + suffix, 0.3, 0.9, 0.3, false).toPersona(),
                new PersonaDefinition("it-rescuer-" + suffix, 0.5, 0.3, 0.9, false).toPersona()));
        PopulationSpec spec = new PopulationSpec(7L, 12,
                Map.of("it-reporter-" + suffix, 1, "it-rescuer-" + suffix, 1));

        UUID delivered = decidedEmail("your order has shipped today", Decision.ALLOW, GroundTruthLabel.SPAM);
        UUID quarantined = decidedEmail("urgent verify your bank login now", Decision.QUARANTINE, GroundTruthLabel.HAM);
        UUID warned = decidedEmail("your invoice is attached for review", Decision.WARN, GroundTruthLabel.HAM);
        UUID blocked = decidedEmail("claim your prize gift card winner", Decision.BLOCK, GroundTruthLabel.SPAM);
        List<DecidedEmail> stream = List.of(
                new DecidedEmail(delivered, Decision.ALLOW, GroundTruthLabel.SPAM),
                new DecidedEmail(quarantined, Decision.QUARANTINE, GroundTruthLabel.HAM),
                new DecidedEmail(warned, Decision.WARN, GroundTruthLabel.HAM),
                new DecidedEmail(blocked, Decision.BLOCK, GroundTruthLabel.SPAM));

        FeedbackRun run = service.simulate(stream, spec);

        List<FeedbackEvent> written = events.findByRunId(run.runId());
        assertThat(written).hasSize(stream.size());
        for (FeedbackEvent event : written) {
            assertThat(event.action()).isIn(FeedbackAction.spaceFor(event.decisionShown()));
            assertThat(event.confidence()).isBetween(0.0, 1.0);
            assertThat(event.delaySeconds()).isGreaterThanOrEqualTo(0L);
            assertThat(event.source()).startsWith("it-");
            if (event.decisionShown().delivers()) {
                assertThat(event.action()).isNotEqualTo(FeedbackAction.RESCUE);
            } else {
                assertThat(event.action()).isNotIn(FeedbackAction.CLICK, FeedbackAction.REPORT);
            }
        }
    }

    @Test
    void the_same_seed_reproduces_the_action_stream() {
        String suffix = UUID.randomUUID().toString();
        personas.seed(List.of(new PersonaDefinition("it-repro-" + suffix, 0.5, 0.5, 0.5, false).toPersona()));
        PopulationSpec spec = new PopulationSpec(99L, 5, Map.of("it-repro-" + suffix, 1));

        UUID a = decidedEmail("first message body here", Decision.ALLOW, GroundTruthLabel.SPAM);
        UUID b = decidedEmail("second message body here", Decision.QUARANTINE, GroundTruthLabel.HAM);
        List<DecidedEmail> stream = List.of(
                new DecidedEmail(a, Decision.ALLOW, GroundTruthLabel.SPAM),
                new DecidedEmail(b, Decision.QUARANTINE, GroundTruthLabel.HAM));

        List<FeedbackEvent> first = events.findByRunId(service.simulate(stream, spec).runId());
        List<FeedbackEvent> second = events.findByRunId(service.simulate(stream, spec).runId());

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).action()).isEqualTo(first.get(i).action());
            assertThat(second.get(i).confidence()).isEqualTo(first.get(i).confidence());
            assertThat(second.get(i).delaySeconds()).isEqualTo(first.get(i).delaySeconds());
        }
    }

    @Test
    void the_decision_stream_loader_returns_decided_and_labeled_emails() {
        UUID emailId = decidedEmail("loader check message", Decision.QUARANTINE, GroundTruthLabel.SPAM);

        List<DecidedEmail> stream = decidedEmails.recentDecidedAndLabeled(10_000);

        assertThat(stream).anySatisfy(decided -> {
            assertThat(decided.emailId()).isEqualTo(emailId);
            assertThat(decided.decisionShown()).isEqualTo(Decision.QUARANTINE);
            assertThat(decided.groundTruth()).isEqualTo(GroundTruthLabel.SPAM);
        });
    }
}
