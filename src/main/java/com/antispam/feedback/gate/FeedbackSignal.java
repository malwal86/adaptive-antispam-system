package com.antispam.feedback.gate;

import com.antispam.feedback.FeedbackAction;
import com.antispam.reputation.ReputationSignal;
import java.util.Optional;

/**
 * Maps a synthetic user's {@link FeedbackAction} to the reputation signal it asserts about the
 * sender (story 07.03). This is the whole of "what does this feedback say about the sender?", kept
 * pure and in one place so the polarity is auditable and unit-testable without a database — the
 * feedback-side parallel of {@link com.antispam.reputation.accrual.DecisionSignal} on the decision
 * side and {@link com.antispam.reputation.AuthGate} on the auth side.
 *
 * <p>The action's space is already conditioned on what the filter showed (07.02), so the polarity
 * follows from the action alone: a {@link FeedbackAction#REPORT} of delivered mail is the user
 * saying "this is spam" — negative evidence for the sender; a {@link FeedbackAction#RESCUE} of
 * withheld mail ("this is legit") and a {@link FeedbackAction#CLICK} (genuine engagement) are
 * positive. {@link FeedbackAction#IGNORE} asserts nothing and carries no signal — it never reaches
 * state. A {@code switch} with no {@code default} means a new action fails to compile here until
 * its polarity is chosen deliberately, never silently defaulting.
 */
public final class FeedbackSignal {

    private FeedbackSignal() {
    }

    /**
     * The reputation signal this action asserts, or empty for {@link FeedbackAction#IGNORE} (no
     * assertion — the gate drops it before any grouping or state change).
     */
    public static Optional<ReputationSignal> of(FeedbackAction action) {
        return switch (action) {
            case CLICK, RESCUE -> Optional.of(ReputationSignal.GOOD);
            case REPORT -> Optional.of(ReputationSignal.BAD);
            case IGNORE -> Optional.empty();
        };
    }
}
