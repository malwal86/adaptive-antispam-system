package com.antispam.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Classification;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionMadeEvent;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The stream's envelope enrichment (making cards read like email): a scenario decision is projected
 * with its from/subject/preview so the console can render a legible card, while ordinary traffic
 * stays PII-free. Also pins the on-the-wire shape — one flat object with the verdict fields plus the
 * optional envelope — since the console parses exactly that.
 */
class DecisionStreamEnrichmentTest {

    private static final EmailParser PARSER = new EmailParser();
    // Mirror Spring Boot's mapper (Instant support) so this pins the shape the console actually receives.
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static final byte[] MOM_RAW = ("""
            From: Mom <mom@family.example>
            To: you@inbox.example
            Subject: Dinner Sunday?
            Authentication-Results: mx.inbox.example; spf=pass; dkim=pass; dmarc=pass
            Content-Type: text/plain; charset=UTF-8

            Hi love — are you free for dinner this Sunday? Dad's making his lasagne.
            """).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

    private static Email email(UUID id, byte[] raw, String source) {
        return new Email(id, new byte[] {1}, raw, PARSER.parse(raw), source, Instant.parse("2026-06-25T08:00:00Z"));
    }

    private static Classification decision(UUID emailId) {
        return new Classification(UUID.randomUUID(), emailId, Decision.ALLOW,
                List.of(), RouteUsed.MODEL, 3L, null, null, "bootstrap-v1", null,
                Instant.parse("2026-06-25T08:01:00Z"));
    }

    @Test
    void a_scenario_decision_is_enriched_with_the_from_subject_and_preview() {
        UUID emailId = UUID.randomUUID();
        Email scenarioEmail = email(emailId, MOM_RAW, "normal-morning-legit");
        DecisionStream stream =
                new DecisionStream(8, id -> id.equals(emailId) ? Optional.of(scenarioEmail) : Optional.empty());

        stream.onDecision(new DecisionMadeEvent(decision(emailId)));

        LiveDecision card = stream.bufferedSince(0).get(0).decision();
        // The friendly display name, not the bare address; the subject; and a readable body preview.
        assertThat(card.sender()).isEqualTo("Mom");
        assertThat(card.subject()).isEqualTo("Dinner Sunday?");
        assertThat(card.preview()).contains("dinner this Sunday");
        assertThat(card.verdict().tier()).isEqualTo("allow");
    }

    @Test
    void ordinary_traffic_is_never_enriched_so_the_feed_stays_pii_free() {
        UUID emailId = UUID.randomUUID();
        // Same email bytes, but ingested from the analyzer (a user's pasted mail), not a scenario.
        Email pasted = email(emailId, MOM_RAW, "console");
        DecisionStream stream =
                new DecisionStream(8, id -> Optional.of(pasted));

        stream.onDecision(new DecisionMadeEvent(decision(emailId)));

        LiveDecision card = stream.bufferedSince(0).get(0).decision();
        assertThat(card.sender()).isNull();
        assertThat(card.subject()).isNull();
        assertThat(card.preview()).isNull();
    }

    @Test
    void the_wire_shape_is_one_flat_object_with_the_optional_envelope() throws Exception {
        UUID emailId = UUID.randomUUID();
        Email scenarioEmail = email(emailId, MOM_RAW, "normal-morning-legit");
        DecisionStream stream = new DecisionStream(8, id -> Optional.of(scenarioEmail));
        stream.onDecision(new DecisionMadeEvent(decision(emailId)));

        LiveDecision card = stream.bufferedSince(0).get(0).decision();
        JsonNode json = JSON.readTree(JSON.writeValueAsString(card));

        // Verdict fields are flattened to the top level (not nested under "verdict"), alongside the
        // envelope — exactly what the console's parseDecision expects.
        assertThat(json.has("verdict")).isFalse();
        assertThat(json.get("tier").asText()).isEqualTo("allow");
        assertThat(json.get("emailId").asText()).isEqualTo(emailId.toString());
        assertThat(json.get("sender").asText()).isEqualTo("Mom");
        assertThat(json.get("subject").asText()).isEqualTo("Dinner Sunday?");
        assertThat(json.get("preview").asText()).contains("dinner this Sunday");
    }

    @Test
    void ordinary_traffic_omits_the_envelope_fields_from_the_json() throws Exception {
        UUID emailId = UUID.randomUUID();
        DecisionStream stream = new DecisionStream(8, id -> Optional.empty());
        stream.onDecision(new DecisionMadeEvent(decision(emailId)));

        LiveDecision card = stream.bufferedSince(0).get(0).decision();
        JsonNode json = JSON.readTree(JSON.writeValueAsString(card));

        assertThat(json.has("sender")).isFalse();
        assertThat(json.has("subject")).isFalse();
        assertThat(json.has("preview")).isFalse();
        assertThat(json.get("tier").asText()).isEqualTo("allow");
    }
}
