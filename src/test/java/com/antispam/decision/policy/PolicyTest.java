package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.decision.Decision;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A policy is the decision regime: its thresholds map a posterior to one of the four
 * tiers (story 04.05). These cases pin the mapping — including the inclusive lower
 * boundaries — and the ordering invariant that keeps the thresholds a valid ladder.
 */
class PolicyTest {

    private static final Policy POLICY = policy(0.50, 0.80, 0.95);

    @Test
    void maps_a_posterior_to_the_tier_its_thresholds_select() {
        assertThat(POLICY.tierFor(0.10)).isEqualTo(Decision.ALLOW);
        assertThat(POLICY.tierFor(0.60)).isEqualTo(Decision.WARN);
        assertThat(POLICY.tierFor(0.85)).isEqualTo(Decision.QUARANTINE);
        assertThat(POLICY.tierFor(0.99)).isEqualTo(Decision.BLOCK);
    }

    @Test
    void treats_each_threshold_as_the_inclusive_floor_of_its_tier() {
        // A posterior exactly on a threshold lands in the more severe tier.
        assertThat(POLICY.tierFor(0.50)).isEqualTo(Decision.WARN);
        assertThat(POLICY.tierFor(0.80)).isEqualTo(Decision.QUARANTINE);
        assertThat(POLICY.tierFor(0.95)).isEqualTo(Decision.BLOCK);
        // Just below a threshold stays in the gentler tier.
        assertThat(POLICY.tierFor(0.4999)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void another_policy_maps_the_same_posterior_to_a_different_tier() {
        // The whole point of policies: thresholds are data, not hardcoded. A stricter
        // policy quarantines what the lenient one only warns on.
        Policy strict = policy(0.20, 0.50, 0.70);

        assertThat(POLICY.tierFor(0.60)).isEqualTo(Decision.WARN);
        assertThat(strict.tierFor(0.60)).isEqualTo(Decision.QUARANTINE);
    }

    @Test
    void rejects_thresholds_that_are_not_a_non_decreasing_ladder() {
        assertThatThrownBy(() -> policy(0.80, 0.50, 0.95))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_thresholds_outside_the_unit_interval() {
        assertThatThrownBy(() -> policy(0.50, 0.80, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_non_positive_burst_threshold() {
        // The burst threshold is a count, not a probability: it must be at least 1, or the detector
        // would fire on every message (a count is always > 0 after recording one).
        assertThatThrownBy(() -> new Policy(
                "test-v1", true, 0.50, 0.80, 0.95, 0.40, 0.05, 0, "bootstrap-v1", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Policy policy(double warn, double quarantine, double block) {
        return new Policy(
                "test-v1", true, warn, quarantine, block, 0.40, 0.05, 20, "bootstrap-v1", Instant.EPOCH);
    }
}
