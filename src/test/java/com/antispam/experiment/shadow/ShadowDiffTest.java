package com.antispam.experiment.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.experiment.shadow.ShadowDiff.Agreement;
import com.antispam.experiment.shadow.ShadowDiff.Direction;
import org.junit.jupiter.api.Test;

/**
 * The diff's contract (story 09.02): equal tiers agree (SAME); a stricter shadow disagrees upward;
 * a more lenient shadow disagrees downward. Severity is the {@link Decision} ladder.
 */
class ShadowDiffTest {

    @Test
    void equal_verdicts_agree_with_no_direction() {
        ShadowDiff diff = ShadowDiff.between(Decision.WARN, Decision.WARN);

        assertThat(diff.agreement()).isEqualTo(Agreement.AGREE);
        assertThat(diff.direction()).isEqualTo(Direction.SAME);
    }

    @Test
    void a_stricter_shadow_disagrees_more_severe() {
        // active WARN, shadow QUARANTINE → the shadow would escalate.
        ShadowDiff diff = ShadowDiff.between(Decision.WARN, Decision.QUARANTINE);

        assertThat(diff.agreement()).isEqualTo(Agreement.DISAGREE);
        assertThat(diff.direction()).isEqualTo(Direction.SHADOW_MORE_SEVERE);
    }

    @Test
    void a_more_lenient_shadow_disagrees_less_severe() {
        // active BLOCK, shadow ALLOW → the shadow would soften.
        ShadowDiff diff = ShadowDiff.between(Decision.BLOCK, Decision.ALLOW);

        assertThat(diff.agreement()).isEqualTo(Agreement.DISAGREE);
        assertThat(diff.direction()).isEqualTo(Direction.SHADOW_LESS_SEVERE);
    }
}
