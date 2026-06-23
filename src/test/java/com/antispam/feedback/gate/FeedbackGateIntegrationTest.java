package com.antispam.feedback.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Decision;
import com.antispam.feedback.FeedbackAction;
import com.antispam.feedback.FeedbackEvent;
import com.antispam.feedback.FeedbackEventRepository;
import com.antispam.feedback.Persona;
import com.antispam.feedback.PersonaDefinition;
import com.antispam.feedback.PersonaRepository;
import com.antispam.ingest.IngestService;
import com.antispam.retrain.RetrainLabel;
import com.antispam.retrain.RetrainLabelRepository;
import com.antispam.seed.GroundTruthLabel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The weighting/corroboration gate end-to-end against real Postgres (story 07.03): raw
 * {@code feedback_events} are weighted by trust × confidence, corroborated by distinct reporters,
 * and only the trusted aggregate reaches the two sinks (reputation events + retrain labels). It pins
 * the story's ACs: a single high-bias/malicious report moves nothing (AC 1); independent
 * corroborated reports move both sinks with weight and provenance (AC 2/AC 4/AC 5); raw feedback
 * never touches state until it passes the gate (AC 3); and re-gating is idempotent.
 *
 * <p>The suite shares one Postgres, so every test uses a unique sender and persona names and scopes
 * its assertions to its own sender key / email ids — never to global counts.
 */
class FeedbackGateIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;
    @Autowired
    private PersonaRepository personas;
    @Autowired
    private FeedbackEventRepository feedbackEvents;
    @Autowired
    private RetrainLabelRepository retrainLabels;
    @Autowired
    private FeedbackGateService gate;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_single_malicious_report_moves_neither_reputation_nor_a_label() {
        String suffix = UUID.randomUUID().toString();
        Persona bomber = seedPersona("bomber-" + suffix, true);
        String sender = "victim-" + suffix + "@legit.test";
        UUID emailId = ingest(sender, true, "your monthly statement is ready");
        UUID runId = UUID.randomUUID();
        feedbackEvents.saveAll(List.of(report(runId, emailId, bomber, GroundTruthLabel.HAM)));

        GateOutcome outcome = gate.gate(runId);

        assertThat(outcome.groupsTrusted()).isZero();
        assertThat(outcome.reputationEventsEmitted()).isZero();
        assertThat(feedbackReputationCount(sender)).isZero();
        assertThat(retrainLabels.findByEmailId(emailId)).isEmpty();
    }

    @Test
    void corroborated_genuine_reports_move_reputation_and_write_weighted_labels_to_both_sinks() {
        String suffix = UUID.randomUUID().toString();
        List<Persona> reporters = List.of(
                seedPersona("reporter-a-" + suffix, false),
                seedPersona("reporter-b-" + suffix, false),
                seedPersona("reporter-c-" + suffix, false),
                seedPersona("reporter-d-" + suffix, false));
        String sender = "campaign-" + suffix + "@spammer.test";
        UUID runId = UUID.randomUUID();
        List<FeedbackEvent> events = new ArrayList<>();
        List<UUID> emailIds = new ArrayList<>();
        for (Persona reporter : reporters) {
            UUID emailId = ingest(sender, false, "claim your prize now winner");
            emailIds.add(emailId);
            events.add(report(runId, emailId, reporter, GroundTruthLabel.SPAM));
        }
        feedbackEvents.saveAll(events);

        GateOutcome outcome = gate.gate(runId);

        // One trusted (sender × BAD × unauthenticated) group → one reputation event, four labels.
        assertThat(outcome.groupsConsidered()).isEqualTo(1);
        assertThat(outcome.groupsTrusted()).isEqualTo(1);
        assertThat(outcome.reputationEventsEmitted()).isEqualTo(1);
        assertThat(outcome.retrainLabelsEmitted()).isEqualTo(4);

        // Reputation sink: exactly one BAD, unauthenticated, feedback-sourced event at the
        // corroborated weight (4 × 1.0 × 0.8 = 3.2, under the 5.0 cap).
        List<Map<String, Object>> repEvents = jdbc.queryForList(
                "select signal, weight, bucket, source from reputation_events where sender_key = ?", sender);
        assertThat(repEvents).hasSize(1);
        assertThat(repEvents.get(0)).containsEntry("signal", "BAD")
                .containsEntry("bucket", "UNAUTHENTICATED")
                .containsEntry("source", "feedback");
        assertThat((double) repEvents.get(0).get("weight")).isCloseTo(3.2, org.assertj.core.api.Assertions.within(1e-9));

        // Label sink: one weighted, provenance-carrying SPAM label per reported email.
        for (UUID emailId : emailIds) {
            List<RetrainLabel> labels = retrainLabels.findByEmailId(emailId);
            assertThat(labels).hasSize(1);
            RetrainLabel label = labels.get(0);
            assertThat(label.label()).isEqualTo(GroundTruthLabel.SPAM);
            assertThat(label.source()).isEqualTo("feedback");
            assertThat(label.weight()).isCloseTo(0.8, org.assertj.core.api.Assertions.within(1e-9));
            // Provenance records the corroboration and the per-item trust/confidence (AC 5).
            assertThat(label.provenance())
                    .contains("\"corroborators\":4")
                    .contains("\"trust\":1.0")
                    .contains("\"confidence\":0.8");
        }
    }

    @Test
    void raw_feedback_does_not_touch_state_until_it_passes_the_gate() {
        String suffix = UUID.randomUUID().toString();
        List<Persona> reporters = List.of(
                seedPersona("pre-a-" + suffix, false),
                seedPersona("pre-b-" + suffix, false),
                seedPersona("pre-c-" + suffix, false));
        String sender = "pre-" + suffix + "@spammer.test";
        UUID runId = UUID.randomUUID();
        List<FeedbackEvent> events = new ArrayList<>();
        for (Persona reporter : reporters) {
            UUID emailId = ingest(sender, false, "verify your account immediately");
            events.add(report(runId, emailId, reporter, GroundTruthLabel.SPAM));
        }
        feedbackEvents.saveAll(events);

        // Feedback persisted but not gated: reputation is untouched — there is no path from
        // feedback_events to state other than the gate (AC 3).
        assertThat(feedbackReputationCount(sender)).isZero();

        gate.gate(runId);

        assertThat(feedbackReputationCount(sender)).isEqualTo(1);
    }

    @Test
    void re_gating_a_run_is_idempotent() {
        String suffix = UUID.randomUUID().toString();
        List<Persona> reporters = List.of(
                seedPersona("idem-a-" + suffix, false),
                seedPersona("idem-b-" + suffix, false),
                seedPersona("idem-c-" + suffix, false));
        String sender = "idem-" + suffix + "@spammer.test";
        UUID runId = UUID.randomUUID();
        List<FeedbackEvent> events = new ArrayList<>();
        for (Persona reporter : reporters) {
            UUID emailId = ingest(sender, false, "your package could not be delivered");
            events.add(report(runId, emailId, reporter, GroundTruthLabel.SPAM));
        }
        feedbackEvents.saveAll(events);

        GateOutcome first = gate.gate(runId);
        GateOutcome second = gate.gate(runId);

        assertThat(first.reputationEventsEmitted()).isEqualTo(1);
        assertThat(first.retrainLabelsEmitted()).isEqualTo(3);
        // Second pass still sees the trusted group, but emits nothing fresh.
        assertThat(second.groupsTrusted()).isEqualTo(1);
        assertThat(second.reputationEventsEmitted()).isZero();
        assertThat(second.retrainLabelsEmitted()).isZero();
        // State reflects exactly one application.
        assertThat(feedbackReputationCount(sender)).isEqualTo(1);
    }

    // --- helpers -------------------------------------------------------------

    private Persona seedPersona(String name, boolean malicious) {
        Persona persona = new PersonaDefinition(name, 0.5, 0.5, 0.5, malicious).toPersona();
        personas.seed(List.of(persona));
        return persona;
    }

    /** Ingests a delivered email from {@code sender}; {@code dmarcPass} controls the accrual bucket. */
    private UUID ingest(String sender, boolean dmarcPass, String body) {
        String tag = UUID.randomUUID().toString();
        StringBuilder raw = new StringBuilder()
                .append("From: ").append(sender).append("\r\n")
                .append("Subject: gate [").append(tag).append("]\r\n");
        if (dmarcPass) {
            raw.append("Authentication-Results: mx.test; dmarc=pass\r\n");
        }
        raw.append("\r\n").append(body).append(" ref:").append(tag);
        return ingestService.ingest(raw.toString().getBytes(StandardCharsets.UTF_8), "gate-it").emailId();
    }

    /** A REPORT (a "this is spam" assertion) on delivered mail, at fixed confidence for determinism. */
    private FeedbackEvent report(UUID runId, UUID emailId, Persona persona, GroundTruthLabel truth) {
        return new FeedbackEvent(UUID.randomUUID(), emailId, persona.id(), runId,
                FeedbackAction.REPORT, 0.8, 10L, Decision.ALLOW, truth, persona.name());
    }

    /** Count of feedback-sourced reputation events for a sender — the gate's reputation footprint. */
    private int feedbackReputationCount(String sender) {
        Integer count = jdbc.queryForObject(
                "select count(*) from reputation_events where sender_key = ? and source = 'feedback'",
                Integer.class, sender);
        return count == null ? 0 : count;
    }
}
