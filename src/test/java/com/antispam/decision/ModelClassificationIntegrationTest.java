package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.model.OnnxModel;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end of the 04.01 model path against a real Postgres and the real
 * in-process ONNX classifier (no mock): mail that matches no hard rule is scored,
 * and the {@code classifications} row records {@code spam_score},
 * {@code phishing_score}, {@code model_version}, and {@code route_used = MODEL}
 * (story 04.01 AC 3) — round-tripping through the database, not just in memory.
 *
 * <p>The complementary case — a hard-rule hit short-circuits before the model and
 * stores NULL scores — anchors the "scores present only on the model route"
 * contract end to end.
 */
class ModelClassificationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ClassificationRepository classifications;

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    @Test
    void model_route_records_scores_and_model_version_on_the_decision_row() {
        Email email = ingest("""
                From: newsletter@good.example
                Subject: WIN A FREE PRIZE NOW!!!
                Authentication-Results: mx.test; spf=none; dkim=fail

                CLICK HERE to claim your reward at http://192.168.10.10/claim
                """);

        Classification decision = decisionService.decide(email);

        assertThat(decision.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(decision.scores()).isNotNull();
        assertThat(decision.scores().modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
        assertThat(decision.scores().spamScore()).isBetween(0.0, 1.0);
        assertThat(decision.scores().phishingScore()).isBetween(0.0, 1.0);

        // The scores are durable: re-read from Postgres and confirm they round-trip.
        assertThat(classifications.findByEmailId(email.id()))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.route()).isEqualTo(RouteUsed.MODEL);
                    assertThat(stored.scores()).isNotNull();
                    assertThat(stored.scores().spamScore()).isEqualTo(decision.scores().spamScore());
                    assertThat(stored.scores().phishingScore()).isEqualTo(decision.scores().phishingScore());
                    assertThat(stored.scores().modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
                });
    }

    @Test
    void hard_rule_route_stores_no_model_scores() {
        Email email = ingest("""
                From: deals@promo.example
                Subject: Act now

                Verify your prize at http://malware.example/login today.
                """);

        Classification decision = decisionService.decide(email);

        assertThat(decision.route()).isEqualTo(RouteUsed.HARD_RULE);
        assertThat(decision.scores()).isNull();

        assertThat(classifications.findByEmailId(email.id()))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.route()).isEqualTo(RouteUsed.HARD_RULE);
                    assertThat(stored.scores()).isNull();
                });
    }
}
