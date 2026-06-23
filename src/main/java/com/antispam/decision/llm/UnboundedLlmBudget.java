package com.antispam.decision.llm;

import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The LLM budget when capping is switched off (story 05.04) — the default for local dev and the
 * full-context tests. Every reservation is granted and reconciliation is a no-op, so the decision
 * path never has to special-case "no budget configured" and no test ever needs Redis just to make
 * an LLM call. Mirrors {@link com.antispam.reputation.cache.NoOpReputationCache}: a real backing
 * store paired with a no-op default selected by the same {@code enabled} flag.
 *
 * <p>Note the asymmetry with the Redis implementation's failure mode: <em>disabled</em> means "do
 * not cap, always allow", whereas {@link RedisLlmBudget} <em>fails closed</em> (denies) when it
 * cannot reach Redis. Disabling is a deliberate operator choice to run uncapped; an outage is not,
 * and there is no second source of truth for spend, so the safe default there is to stop spending.
 */
@Component
@ConditionalOnProperty(name = "antispam.llm.budget.enabled", havingValue = "false", matchIfMissing = true)
public class UnboundedLlmBudget implements LlmBudget {

    @Override
    public BudgetReservation tryReserve() {
        return BudgetReservation.granted(BigDecimal.ZERO, null, null);
    }

    @Override
    public void reconcile(BudgetReservation reservation, BigDecimal actualCostUsd) {
        // Intentionally no-op: with capping disabled there is no counter to true up.
    }
}
