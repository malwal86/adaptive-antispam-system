package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * The LLM budget-cap gauges in isolation (story 13.01). The running spend is already a counter
 * ({@code antispam.llm.cost.usd}, story 05.02); these gauges export the <em>caps</em> the spend is
 * measured against, so a dashboard can draw cost-vs-cap and budget-remaining (= cap − spend). The
 * gauges read {@link LlmBudgetCaps} live, so an operator's runtime cap change (story 12.02) is
 * reflected on the next scrape.
 */
class LlmBudgetGaugesTest {

    private static LlmBudgetCaps caps(double daily, double monthly) {
        return new LlmBudgetCaps(new LlmBudgetProperties(true, daily, monthly, 0.01));
    }

    @Test
    void exports_the_daily_and_monthly_caps_as_scope_tagged_gauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new LlmBudgetGauges(registry, caps(0.50, 5.00));

        assertThat(registry.get(LlmBudgetGauges.CAP).tag("scope", "daily").gauge().value())
                .isCloseTo(0.50, within(1e-9));
        assertThat(registry.get(LlmBudgetGauges.CAP).tag("scope", "monthly").gauge().value())
                .isCloseTo(5.00, within(1e-9));
    }

    @Test
    void the_gauges_track_a_runtime_cap_change() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmBudgetCaps liveCaps = caps(0.50, 5.00);
        new LlmBudgetGauges(registry, liveCaps);

        liveCaps.update(1.00, 10.00);

        assertThat(registry.get(LlmBudgetGauges.CAP).tag("scope", "daily").gauge().value())
                .isCloseTo(1.00, within(1e-9));
        assertThat(registry.get(LlmBudgetGauges.CAP).tag("scope", "monthly").gauge().value())
                .isCloseTo(10.00, within(1e-9));
    }
}
