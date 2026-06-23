package com.antispam.decision.llm;

import java.math.BigDecimal;

/**
 * The outcome of an atomic check+reserve against the LLM budget (story 05.04), and the token the
 * caller hands back to {@link LlmBudget#reconcile} once the call's real cost is known.
 *
 * <p><b>Why reserve-then-reconcile.</b> A call's true cost is only known <em>after</em> it returns
 * (it depends on token usage), but the cap has to be enforced <em>before</em> the call or a race of
 * concurrent calls could overspend. So a conservative upper-bound amount is reserved atomically up
 * front; the call runs only if the reservation is {@link #granted()}; afterwards the counter is
 * trued up to the actual spend. Because the reserved amount is an upper bound, the running total can
 * never exceed the cap between reserve and reconcile — that is the "0 overspend under a race"
 * guarantee (AC 1).
 *
 * <p>A granted reservation carries the {@code reservedUsd} and the two window keys it incremented,
 * so {@link LlmBudget#reconcile} adjusts exactly the windows that were charged. A denied
 * reservation carries no keys and a {@link #deniedScope()} explaining which cap was hit.
 *
 * @param granted     whether the call may proceed
 * @param deniedScope which cap denied it ({@code null} when granted)
 * @param reservedUsd the upper-bound amount charged up front ({@link BigDecimal#ZERO} when denied)
 * @param dayKey      the Redis daily-counter key charged ({@code null} when denied)
 * @param monthKey    the Redis monthly-counter key charged ({@code null} when denied)
 */
public record BudgetReservation(
        boolean granted, BudgetScope deniedScope, BigDecimal reservedUsd, String dayKey, String monthKey) {

    /** A granted reservation charged against the given day/month keys. */
    public static BudgetReservation granted(BigDecimal reservedUsd, String dayKey, String monthKey) {
        return new BudgetReservation(true, null, reservedUsd, dayKey, monthKey);
    }

    /** A denied reservation: no keys were charged; {@code scope} says which cap was hit. */
    public static BudgetReservation denied(BudgetScope scope) {
        return new BudgetReservation(false, scope, BigDecimal.ZERO, null, null);
    }

    /**
     * A denial because the budget store could not be reached (story 05.04, fail-closed). Distinct
     * from a cap hit — it carries no {@link #deniedScope()} — so observability can tell "budget
     * spent" apart from "cost-control infrastructure down".
     */
    public static BudgetReservation unavailable() {
        return new BudgetReservation(false, null, BigDecimal.ZERO, null, null);
    }
}
