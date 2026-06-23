package com.antispam.decision.llm;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Makes the LLM fallback observable (story 05.02). The LLM is the expensive, failure-prone lever,
 * so the numbers that matter operationally are: how often a call produced a valid verdict vs.
 * degraded, why it degraded, how often the schema retry fired, and the running cost. Each is a
 * counter so the degraded fraction, the retry rate, and spend are directly queryable; the rolling
 * budget cap that acts on the cost is story 05.04.
 */
@Component
public class LlmMeter {

    /** Counter, tagged {@code outcome=valid|degraded} — the valid vs. degraded split. */
    static final String CALL = "antispam.llm.call";

    /** Counter — incremented once whenever the single schema retry was used. */
    static final String SCHEMA_RETRY = "antispam.llm.schema_retry";

    /** Counter, tagged {@code reason} — attributes degraded calls to schema vs. unavailable. */
    static final String DEGRADED = "antispam.llm.degraded";

    /** Counter — running sum of {@code llm_cost_usd} across calls. */
    static final String COST = "antispam.llm.cost.usd";

    /** Counter, tagged {@code scope=daily|monthly} — calls the budget cap denied (story 05.04). */
    static final String BUDGET_DENIED = "antispam.llm.budget.denied";

    /**
     * Counter, tagged {@code state} — quarantine-pending resolutions by lifecycle state (story
     * 05.06): pending (withheld), promoted, confirmed, degraded. The degraded count is the
     * observable "running degraded" banner signal (AC 5).
     */
    static final String RESOLUTION = "antispam.llm.resolution";

    /** Counter — async resolutions that blew the SLA deadline and fail-degraded (story 05.06). */
    static final String SLA_TIMEOUT = "antispam.llm.sla.timeout";

    private final MeterRegistry meters;

    @Autowired
    public LlmMeter(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Records a call that produced a validated verdict; a second attempt means the retry fired. */
    public void recordValid(int attempts, BigDecimal costUsd) {
        meters.counter(CALL, "outcome", "valid").increment();
        if (attempts > 1) {
            meters.counter(SCHEMA_RETRY).increment();
        }
        recordCost(costUsd);
    }

    /** Records a degraded call, its reason, and (for a schema degrade after a retry) the retry. */
    public void recordDegraded(DegradeReason reason, int attempts, BigDecimal costUsd) {
        meters.counter(CALL, "outcome", "degraded").increment();
        meters.counter(DEGRADED, "reason", reason.tag()).increment();
        if (reason == DegradeReason.SCHEMA && attempts > 1) {
            meters.counter(SCHEMA_RETRY).increment();
        }
        recordCost(costUsd);
    }

    /**
     * Records a call the budget cap denied (story 05.04): it counts as a degraded call attributed
     * to {@link DegradeReason#BUDGET}, plus a scope-tagged budget counter so the daily-vs-monthly
     * split of stopped spend is directly queryable. No cost — no call was made.
     */
    public void recordBudgetDenied(BudgetScope scope) {
        meters.counter(CALL, "outcome", "degraded").increment();
        meters.counter(DEGRADED, "reason", DegradeReason.BUDGET.tag()).increment();
        meters.counter(BUDGET_DENIED, "scope", scope.tag()).increment();
    }

    /** Records a quarantine-pending resolution reaching {@code state} (story 05.06). */
    public void recordResolution(ResolutionState state) {
        meters.counter(RESOLUTION, "state", state.name().toLowerCase(java.util.Locale.ROOT)).increment();
    }

    /** Records an async resolution that exceeded the SLA deadline and fail-degraded (story 05.06). */
    public void recordSlaTimeout() {
        meters.counter(SLA_TIMEOUT).increment();
    }

    private void recordCost(BigDecimal costUsd) {
        if (costUsd.signum() > 0) {
            meters.counter(COST).increment(costUsd.doubleValue());
        }
    }
}
