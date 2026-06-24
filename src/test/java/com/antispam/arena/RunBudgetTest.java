package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The run's hard spend ceiling (story 08.02): the attacker may make a call only while the next
 * call's cost still fits under the cap, exactly as the LLM budget gate (05.04) admits a call only on
 * a granted reservation. This is what makes the loop terminate on budget rather than hope (AC 5).
 */
class RunBudgetTest {

    private static final BigDecimal COST = new BigDecimal("0.10");

    @Test
    void admits_calls_until_the_next_one_would_breach_the_cap() {
        RunBudget budget = new RunBudget(new BigDecimal("0.25"));

        // Two calls fit (0.20 ≤ 0.25); a third would reach 0.30 > 0.25 and is refused — a hard stop.
        assertThat(budget.canAfford(COST)).isTrue();
        budget.charge(COST);
        assertThat(budget.canAfford(COST)).isTrue();
        budget.charge(COST);
        assertThat(budget.canAfford(COST)).isFalse();
        assertThat(budget.spentUsd()).isEqualByComparingTo("0.20");
    }

    @Test
    void admits_a_call_that_lands_exactly_on_the_cap() {
        RunBudget budget = new RunBudget(new BigDecimal("0.20"));
        budget.charge(COST);

        // 0.10 + 0.10 == 0.20: spending up to the ceiling is allowed; only exceeding it is refused.
        assertThat(budget.canAfford(COST)).isTrue();
    }

    @Test
    void rejects_a_non_positive_cap() {
        assertThatThrownBy(() -> new RunBudget(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
