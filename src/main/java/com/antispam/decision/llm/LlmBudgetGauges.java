package com.antispam.decision.llm;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exports the LLM spend caps as gauges (story 13.01). The running spend is already a counter
 * ({@code antispam.llm.cost.usd}, story 05.02); to draw the operationally interesting picture — cost
 * vs. the cap, and budget-remaining (= cap − spend) — a dashboard also needs the caps as a series.
 * These two gauges supply that, tagged {@code scope=daily|monthly}.
 *
 * <p>They read {@link LlmBudgetCaps} live on every scrape, so an operator's runtime cap change
 * (story 12.02) is reflected immediately — the cap line on the Grafana panel moves with the override
 * rather than freezing at the value baked in at startup.
 */
@Component
public class LlmBudgetGauges {

    /** Gauge name; tagged {@code scope=daily|monthly} — the spend cap in force right now. */
    static final String CAP = "antispam.llm.budget.cap.usd";

    @Autowired
    public LlmBudgetGauges(MeterRegistry meters, LlmBudgetCaps caps) {
        Gauge.builder(CAP, caps, c -> c.current().dailyCapUsd())
                .tag("scope", "daily")
                .description("The LLM daily spend cap in force; budget-remaining = this − cost.usd")
                .baseUnit("usd")
                .register(meters);
        Gauge.builder(CAP, caps, c -> c.current().monthlyCapUsd())
                .tag("scope", "monthly")
                .description("The LLM monthly spend cap in force; budget-remaining = this − cost.usd")
                .baseUnit("usd")
                .register(meters);
    }
}
