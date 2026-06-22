package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The budget config's validation (story 05.04). The caps are operator-tunable, so the binding
 * rejects nonsensical combinations loudly at startup rather than letting a typo silently disable
 * cost control: negative caps, a non-positive reservation (which would make every check a no-op),
 * or a daily cap above the monthly cap (which makes the sub-cap meaningless).
 */
class LlmBudgetPropertiesTest {

    @Test
    void accepts_a_sane_configuration() {
        assertThatCode(() -> new LlmBudgetProperties(true, 0.50, 5.00, 0.01))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_a_negative_daily_cap() {
        assertThatThrownBy(() -> new LlmBudgetProperties(true, -0.01, 5.00, 0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void rejects_a_negative_monthly_cap() {
        assertThatThrownBy(() -> new LlmBudgetProperties(true, 0.50, -5.00, 0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void rejects_a_non_positive_reservation() {
        assertThatThrownBy(() -> new LlmBudgetProperties(true, 0.50, 5.00, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-call-reservation-usd");
    }

    @Test
    void rejects_a_daily_cap_above_the_monthly_cap() {
        assertThatThrownBy(() -> new LlmBudgetProperties(true, 6.00, 5.00, 0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed monthly");
    }
}
