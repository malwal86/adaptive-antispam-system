package com.antispam.experiment.shadow;

import com.antispam.decision.Decision;

/**
 * The diff between an active (enforced) verdict and a shadow (logged-only) verdict for one live
 * email (story 09.02): whether they agree, and — when they don't — which way the shadow policy
 * would move the decision. This is the unit of promotion evidence the gate (Epic 10) aggregates: a
 * candidate that only ever escalates carries a different risk than one that softens, so the
 * direction matters as much as the disagreement rate.
 *
 * @param agreement whether the two tiers match
 * @param direction which way the shadow verdict differs from the active one (SAME when they agree)
 */
public record ShadowDiff(Agreement agreement, Direction direction) {

    /** Whether the active and shadow verdicts land on the same tier. */
    public enum Agreement {
        AGREE,
        DISAGREE
    }

    /** Which way the shadow verdict moves relative to the active one, by tier severity. */
    public enum Direction {
        SAME,
        SHADOW_MORE_SEVERE,
        SHADOW_LESS_SEVERE
    }

    /**
     * The diff between an {@code active} and a {@code shadow} verdict. Severity is {@link Decision}'s
     * natural order (ALLOW &lt; WARN &lt; QUARANTINE &lt; BLOCK).
     */
    public static ShadowDiff between(Decision active, Decision shadow) {
        int bySeverity = shadow.compareTo(active);
        if (bySeverity == 0) {
            return new ShadowDiff(Agreement.AGREE, Direction.SAME);
        }
        return new ShadowDiff(
                Agreement.DISAGREE,
                bySeverity > 0 ? Direction.SHADOW_MORE_SEVERE : Direction.SHADOW_LESS_SEVERE);
    }
}
