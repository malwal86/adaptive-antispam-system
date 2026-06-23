package com.antispam.decision.llm;

import java.math.BigDecimal;

/**
 * The result of running the LLM fallback for one routed decision (story 05.02): either a validated
 * {@link LlmVerdict}, or a <em>degraded</em> outcome carrying no verdict when the call could not
 * produce one (the provider was unavailable, or two successive responses failed the schema). Either
 * way it carries what the classification row records — how long the call took and what it cost — so
 * {@code route_used}, latency, and {@code llm_cost_usd} are observable for every LLM call (AC 5).
 *
 * <p>A degraded outcome is the signal, not the resolution: this story leaves the provisional
 * fast-path tier standing. Story 05.06 turns degradation into the conservative-bias decision and
 * the quarantine-pending SLA.
 *
 * @param verdict   the validated verdict, or {@code null} when degraded (see {@link #degraded()})
 * @param latencyMs total wall-clock milliseconds spent across all attempts (≥ 0)
 * @param costUsd   accumulated cost across all attempts (≥ 0; never null)
 * @param attempts  how many provider calls were made: 0 when none was made (the budget cap denied
 *                  it, story 05.04); 1 on a clean call or an immediate unavailability; 2 when the
 *                  first response failed the schema
 */
public record LlmOutcome(LlmVerdict verdict, long latencyMs, BigDecimal costUsd, int attempts) {

    public LlmOutcome {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs cannot be negative");
        }
        if (costUsd == null || costUsd.signum() < 0) {
            throw new IllegalArgumentException("costUsd must be non-null and non-negative");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts cannot be negative");
        }
    }

    /** A successful outcome carrying the validated verdict. */
    public static LlmOutcome valid(LlmVerdict verdict, long latencyMs, BigDecimal costUsd, int attempts) {
        if (verdict == null) {
            throw new IllegalArgumentException("a valid outcome requires a verdict");
        }
        return new LlmOutcome(verdict, latencyMs, costUsd, attempts);
    }

    /** A degraded outcome: no verdict, but the latency and cost already incurred are still recorded. */
    public static LlmOutcome degraded(long latencyMs, BigDecimal costUsd, int attempts) {
        return new LlmOutcome(null, latencyMs, costUsd, attempts);
    }

    /**
     * A degraded outcome where no provider call was made at all (story 05.04): the budget cap denied
     * the call, or the budget store itself was unreachable. No verdict, zero cost, zero attempts.
     */
    public static LlmOutcome notAttempted() {
        return new LlmOutcome(null, 0L, BigDecimal.ZERO, 0);
    }

    /** Whether the call failed to produce a validated verdict and the decision must fail-degrade. */
    public boolean degraded() {
        return verdict == null;
    }
}
