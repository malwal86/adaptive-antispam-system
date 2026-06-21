package com.antispam.reputation.accrual;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.reputation.ReputationSignal;
import org.junit.jupiter.api.Test;

/**
 * The verdict-to-signal map in isolation (story 03.05): how the four-tier
 * {@link Decision} a sender's mail earns translates into the {@link ReputationSignal}
 * accrued for that sender. The split is by severity — a delivered/banner verdict is
 * positive evidence, a quarantined/blocked verdict is negative — so every tier maps,
 * with the boundary sitting between {@code WARN} and {@code QUARANTINE}.
 */
class DecisionSignalTest {

    @Test
    void allow_is_a_good_signal() {
        assertThat(DecisionSignal.of(Decision.ALLOW)).isEqualTo(ReputationSignal.GOOD);
    }

    @Test
    void warn_is_still_a_good_signal() {
        // WARN delivers the mail (with a banner): the message was let through, so it is
        // positive evidence for the sender, not negative.
        assertThat(DecisionSignal.of(Decision.WARN)).isEqualTo(ReputationSignal.GOOD);
    }

    @Test
    void quarantine_is_a_bad_signal() {
        assertThat(DecisionSignal.of(Decision.QUARANTINE)).isEqualTo(ReputationSignal.BAD);
    }

    @Test
    void block_is_a_bad_signal() {
        assertThat(DecisionSignal.of(Decision.BLOCK)).isEqualTo(ReputationSignal.BAD);
    }

    @Test
    void every_decision_tier_maps_to_a_signal() {
        // No tier may be left unmapped: a new verdict tier must force a deliberate choice
        // here rather than silently defaulting.
        for (Decision decision : Decision.values()) {
            assertThat(DecisionSignal.of(decision)).isNotNull();
        }
    }
}
