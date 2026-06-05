package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Severity merge semantics: the worst tier wins when several signals fire. */
class DecisionTest {

    @Test
    void mostSevere_picks_block_over_lesser_tiers() {
        assertThat(Decision.mostSevere(List.of(Decision.ALLOW, Decision.BLOCK, Decision.QUARANTINE)))
                .isEqualTo(Decision.BLOCK);
    }

    @Test
    void mostSevere_picks_quarantine_over_warn_and_allow() {
        assertThat(Decision.mostSevere(List.of(Decision.WARN, Decision.QUARANTINE, Decision.ALLOW)))
                .isEqualTo(Decision.QUARANTINE);
    }

    @Test
    void mostSevere_of_a_single_decision_is_that_decision() {
        assertThat(Decision.mostSevere(List.of(Decision.WARN))).isEqualTo(Decision.WARN);
    }

    @Test
    void mostSevere_of_no_decisions_is_rejected() {
        assertThatThrownBy(() -> Decision.mostSevere(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
