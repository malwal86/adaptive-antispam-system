package com.antispam.feedback;

import com.antispam.decision.Decision;
import java.util.List;

/**
 * What a synthetic user does with an email (story 07.02). The space is conditioned on what the
 * filter actually showed them (PRD §Subsystem 7): an action is only meaningful for mail the user
 * can act on — you never rescue a mail that was delivered, nor report one you never received.
 */
public enum FeedbackAction {

    /** Engage with delivered mail (e.g. click a link) — only on delivered mail. */
    CLICK,

    /** Mark delivered mail as spam — only on delivered mail. */
    REPORT,

    /** Pull withheld mail back to the inbox ("this is legit") — only on withheld mail. */
    RESCUE,

    /** Take no action. Always available, whatever the filter did. */
    IGNORE;

    /**
     * The actions available given the verdict the user was shown: delivered mail
     * ({@link Decision#delivers()} — {@code ALLOW}/{@code WARN}) can be {@link #CLICK}ed,
     * {@link #REPORT}ed, or {@link #IGNORE}d; withheld mail ({@code QUARANTINE}/{@code BLOCK})
     * can be {@link #RESCUE}d or {@link #IGNORE}d. The order is fixed so categorical sampling
     * over the space is reproducible.
     */
    public static List<FeedbackAction> spaceFor(Decision decisionShown) {
        return decisionShown.delivers()
                ? List.of(CLICK, REPORT, IGNORE)
                : List.of(RESCUE, IGNORE);
    }
}
