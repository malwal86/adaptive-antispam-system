package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The runtime-adjustable LLM caps (story 12.02): seeded from config, validated on update. */
class LlmBudgetCapsTest {

    private static final LlmBudgetProperties PROPS = new LlmBudgetProperties(true, 0.50, 5.00, 0.01);

    @Test
    void seeds_from_configured_properties() {
        LlmBudgetCaps caps = new LlmBudgetCaps(PROPS);
        assertThat(caps.current().dailyCapUsd()).isEqualTo(0.50);
        assertThat(caps.current().monthlyCapUsd()).isEqualTo(5.00);
    }

    @Test
    void update_replaces_the_caps_in_force() {
        LlmBudgetCaps caps = new LlmBudgetCaps(PROPS);
        LlmBudgetCaps.Caps updated = caps.update(0.10, 2.00);
        assertThat(updated.dailyCapUsd()).isEqualTo(0.10);
        assertThat(caps.current().monthlyCapUsd()).isEqualTo(2.00);
    }

    @Test
    void rejects_negative_caps() {
        LlmBudgetCaps caps = new LlmBudgetCaps(PROPS);
        assertThatThrownBy(() -> caps.update(-0.01, 5.00))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_daily_cap_above_the_monthly_cap() {
        LlmBudgetCaps caps = new LlmBudgetCaps(PROPS);
        assertThatThrownBy(() -> caps.update(6.00, 5.00))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily cap cannot exceed");
    }
}
