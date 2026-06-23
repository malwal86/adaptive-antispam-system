package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The no-op budget used when capping is off (story 05.04): it must always grant and never need a
 * backing store, so local dev and the full-context tests make LLM calls without Redis. Reconcile is
 * a no-op that tolerates any input.
 */
class UnboundedLlmBudgetTest {

    private final UnboundedLlmBudget budget = new UnboundedLlmBudget();

    @Test
    void always_grants_with_no_charge() {
        BudgetReservation reservation = budget.tryReserve();

        assertThat(reservation.granted()).isTrue();
        assertThat(reservation.reservedUsd()).isEqualByComparingTo("0");
    }

    @Test
    void reconcile_is_a_no_op() {
        assertThatCode(() -> budget.reconcile(budget.tryReserve(), new BigDecimal("0.02")))
                .doesNotThrowAnyException();
    }
}
