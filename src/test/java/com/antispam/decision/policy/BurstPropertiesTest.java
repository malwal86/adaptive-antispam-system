package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.decision.Decision;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The burst detector's configuration invariants (story 06.01): when enabled, the window must be a
 * positive duration and the escalation tier must be more severe than ALLOW, or the detector could
 * never measure velocity or change a tier. When disabled the knobs are irrelevant, so they are not
 * validated — the no-op detector is wired regardless.
 */
class BurstPropertiesTest {

    @Test
    void accepts_a_positive_window_and_a_severe_escalation_tier_when_enabled() {
        BurstProperties props =
                new BurstProperties(true, Duration.ofSeconds(60), Decision.QUARANTINE);

        assertThat(props.window()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.escalateTo()).isEqualTo(Decision.QUARANTINE);
    }

    @Test
    void rejects_a_non_positive_window_when_enabled() {
        assertThatThrownBy(() -> new BurstProperties(true, Duration.ZERO, Decision.QUARANTINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BurstProperties(true, null, Decision.QUARANTINE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_an_escalation_to_allow_which_could_never_change_a_tier_when_enabled() {
        assertThatThrownBy(() -> new BurstProperties(true, Duration.ofSeconds(60), Decision.ALLOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void does_not_validate_the_knobs_when_disabled() {
        // Disabled: NoBurstOverride is wired and the window/tier are never read, so even nonsense
        // values must not stop the context from starting.
        assertThatCode(() -> new BurstProperties(false, null, null))
                .doesNotThrowAnyException();
    }
}
