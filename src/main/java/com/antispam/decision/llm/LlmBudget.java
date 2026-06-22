package com.antispam.decision.llm;

import java.math.BigDecimal;

/**
 * The LLM spend gate (story 05.04): bounds cost <em>by design</em> rather than by hope (PRD
 * §Subsystem 5, §Cost). Every LLM call must first {@link #tryReserve()} against a daily sub-cap and
 * a rolling monthly cap; only a {@link BudgetReservation#granted() granted} reservation may proceed.
 * When the call finishes, {@link #reconcile} trues the counters up to the real spend.
 *
 * <p>This is the seam the project owns, so the spend policy is testable without a network: the
 * decision path depends on this interface, not on Redis. Two implementations back it —
 * {@link RedisLlmBudget} enforces the caps atomically in Redis; {@link UnboundedLlmBudget} is the
 * no-op default (always grants) for local dev and the full-context tests, mirroring how the
 * reputation cache pairs a real backing store with a no-op default.
 *
 * <p><b>Reserve-then-reconcile, not charge-after.</b> The amount reserved is a conservative
 * upper bound on a single call (covering its one retry), charged atomically before the call. The
 * call runs only if granted; afterwards the actual cost replaces the reservation. Because the
 * reservation is an upper bound, the running total never exceeds the cap even under concurrent
 * calls — the property the race test pins (AC 1).
 */
public interface LlmBudget {

    /**
     * Atomically checks both caps and, if neither would be exceeded by a reserved upper-bound
     * amount, charges it and returns a {@link BudgetReservation#granted() granted} reservation;
     * otherwise returns a {@link BudgetReservation#denied denial} naming the cap that was hit. The
     * daily sub-cap is checked first, so a denial is attributed to the day before the month.
     */
    BudgetReservation tryReserve();

    /**
     * Trues a granted reservation up to the call's real cost by adjusting the charged windows by
     * {@code (actualCostUsd - reservedUsd)}. A no-op for a denied reservation. Because the actual
     * cost is at most the reserved upper bound, the adjustment only ever releases budget back.
     *
     * @param reservation   the token returned by the matching {@link #tryReserve()}
     * @param actualCostUsd the call's real cost (≥ 0); {@link BigDecimal#ZERO} if no call was made
     */
    void reconcile(BudgetReservation reservation, BigDecimal actualCostUsd);
}
