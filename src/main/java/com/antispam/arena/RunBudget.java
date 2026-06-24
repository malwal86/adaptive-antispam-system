package com.antispam.arena;

import java.math.BigDecimal;

/**
 * The hard spend ceiling of one attack run (story 08.02): a mutable ledger over a fixed USD cap.
 * Each attacker call costs a known amount, so the loop {@link #canAfford checks} before every call
 * and {@link #charge charges} after it — the same admit-only-if-it-fits discipline the LLM budget
 * gate (story 05.04) applies to the defender's fallback. When the next call would breach the cap the
 * loop stops with partial results recorded (AC 5); the loop, not this object, owns that decision, so
 * this stays a small, testable value type.
 *
 * <p>Single-run-scoped and single-threaded by construction: a run executes its generations on one
 * thread, so no synchronization is needed here (unlike the cross-request {@code RedisLlmBudget}).
 */
final class RunBudget {

    private final BigDecimal capUsd;
    private BigDecimal spentUsd = BigDecimal.ZERO;

    RunBudget(BigDecimal capUsd) {
        if (capUsd == null || capUsd.signum() <= 0) {
            throw new IllegalArgumentException("run budget cap must be positive: " + capUsd);
        }
        this.capUsd = capUsd;
    }

    /** Whether a call costing {@code costUsd} still fits under the cap — the spend ceiling is inclusive. */
    boolean canAfford(BigDecimal costUsd) {
        return spentUsd.add(costUsd).compareTo(capUsd) <= 0;
    }

    /** Records {@code costUsd} as spent. The caller charges after a call it has confirmed it can afford. */
    void charge(BigDecimal costUsd) {
        spentUsd = spentUsd.add(costUsd);
    }

    /** The total charged so far, recorded on the run as {@code spent_usd}. */
    BigDecimal spentUsd() {
        return spentUsd;
    }
}
