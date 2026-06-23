package com.antispam.feedback.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationSignal;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Corroboration aggregation + threshold (story 07.03): distinct-reporter count and summed weight,
 * with a group trusted only when it clears both. The count defeats report-bombing (one persona can
 * never clear it, AC 1); the weight defeats low-trust corroboration (down-weighted bombers sum
 * below it, AC 1) while genuine corroboration clears it (AC 2).
 */
class CorroborationGateTest {

    private static final int MIN_CORROBORATORS = 3;
    private static final double MIN_WEIGHT = 1.5;

    private static WeightedFeedback item(UUID personaId, double weight) {
        return new WeightedFeedback(UUID.randomUUID(), personaId, "sender@evil.test",
                ReputationSignal.BAD, ReputationBucket.UNAUTHENTICATED, GroundTruthLabel.SPAM,
                0.9, weight, weight);
    }

    @Test
    void a_single_persona_never_clears_the_corroborator_floor_even_at_full_weight() {
        UUID lone = UUID.randomUUID();
        // Same persona acting many times is still one reporter — and full-weight items still fail.
        CorroborationResult result = CorroborationGate.evaluate(
                List.of(item(lone, 1.0), item(lone, 1.0), item(lone, 1.0)), MIN_CORROBORATORS, MIN_WEIGHT);

        assertThat(result.corroborators()).isEqualTo(1);
        assertThat(result.aggregateWeight()).isCloseTo(3.0, within(1e-12));
        assertThat(result.trusted()).isFalse();
    }

    @Test
    void independent_good_faith_reports_clear_both_thresholds() {
        CorroborationResult result = CorroborationGate.evaluate(
                List.of(item(UUID.randomUUID(), 0.7), item(UUID.randomUUID(), 0.7), item(UUID.randomUUID(), 0.7)),
                MIN_CORROBORATORS, MIN_WEIGHT);

        assertThat(result.corroborators()).isEqualTo(3);
        assertThat(result.aggregateWeight()).isCloseTo(2.1, within(1e-12));
        assertThat(result.trusted()).isTrue();
    }

    @Test
    void enough_reporters_but_too_little_weight_is_blocked() {
        // Three distinct down-weighted (malicious) reporters: count clears, weight does not.
        CorroborationResult result = CorroborationGate.evaluate(
                List.of(item(UUID.randomUUID(), 0.09), item(UUID.randomUUID(), 0.09), item(UUID.randomUUID(), 0.09)),
                MIN_CORROBORATORS, MIN_WEIGHT);

        assertThat(result.corroborators()).isEqualTo(3);
        assertThat(result.aggregateWeight()).isCloseTo(0.27, within(1e-12));
        assertThat(result.trusted()).isFalse();
    }

    @Test
    void rejects_an_empty_group() {
        assertThatThrownBy(() -> CorroborationGate.evaluate(List.of(), MIN_CORROBORATORS, MIN_WEIGHT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
