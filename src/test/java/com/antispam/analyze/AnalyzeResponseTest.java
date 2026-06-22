package com.antispam.analyze;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Classification;
import com.antispam.decision.Decision;
import com.antispam.decision.ModelScores;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The response DTO faithfully projects a persisted {@link Classification} onto the
 * analyzer contract: lowercase tier/route tokens the UI keys colours off, the
 * canonical reason-code names as chips, and a grounded explanation.
 */
class AnalyzeResponseTest {

    @Test
    void maps_a_hard_rule_block_to_lowercase_tokens_and_chips() {
        UUID emailId = UUID.randomUUID();
        UUID classificationId = UUID.randomUUID();
        Instant decidedAt = Instant.parse("2026-06-05T12:00:00Z");
        Classification classification = new Classification(
                classificationId, emailId, Decision.BLOCK,
                List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 3L, null, null, decidedAt);

        AnalyzeResponse response = AnalyzeResponse.from(classification, false);

        assertThat(response.emailId()).isEqualTo(emailId);
        assertThat(response.classificationId()).isEqualTo(classificationId);
        assertThat(response.tier()).isEqualTo("block");
        assertThat(response.routeUsed()).isEqualTo("hard_rule");
        assertThat(response.reasonCodes()).containsExactly("KNOWN_BAD_URL");
        assertThat(response.latencyMs()).isEqualTo(3L);
        assertThat(response.explanation()).contains("known-malicious host");
        assertThat(response.decidedAt()).isEqualTo(decidedAt);
        assertThat(response.duplicate()).isFalse();
        // A hard-rule verdict short-circuits the model, so it carries no scores.
        assertThat(response.spamScore()).isNull();
        assertThat(response.phishingScore()).isNull();
        assertThat(response.modelVersion()).isNull();
        assertThat(response.calibratedConfidence()).isNull();
    }

    @Test
    void maps_a_model_allow_with_no_reasons_and_surfaces_the_scores() {
        Classification classification = new Classification(
                UUID.randomUUID(), UUID.randomUUID(), Decision.ALLOW,
                List.of(), RouteUsed.MODEL, 0L,
                new ModelScores(0.12, 0.03, "bootstrap-v1"), null, Instant.now());

        AnalyzeResponse response = AnalyzeResponse.from(classification, true);

        assertThat(response.tier()).isEqualTo("allow");
        assertThat(response.routeUsed()).isEqualTo("model");
        assertThat(response.reasonCodes()).isEmpty();
        assertThat(response.explanation()).contains("provisionally allowed");
        assertThat(response.duplicate()).isTrue();
        // The model route surfaces its raw scores for the card / API.
        assertThat(response.spamScore()).isEqualTo(0.12);
        assertThat(response.phishingScore()).isEqualTo(0.03);
        assertThat(response.modelVersion()).isEqualTo("bootstrap-v1");
        // With no calibration fit, the served confidence is the raw P(abuse) = spam + phish.
        assertThat(response.calibratedConfidence()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
        // No calibration installed ⇒ the score was not fused, so there is no posterior.
        assertThat(response.posterior()).isNull();
        assertThat(response.uncertaintyBand()).isNull();
    }
}
