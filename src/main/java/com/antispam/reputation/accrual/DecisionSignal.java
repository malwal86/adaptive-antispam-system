package com.antispam.reputation.accrual;

import com.antispam.decision.Decision;
import com.antispam.reputation.ReputationSignal;

/**
 * Maps the verdict an email earns to the reputation signal its sender accrues
 * (story 03.05). This is the whole of "what does this decision say about the
 * sender?", kept pure and in one place so the policy is auditable and unit-testable
 * without a database — the parallel, on the decision side, of {@link
 * com.antispam.reputation.AuthGate} on the auth side.
 *
 * <p>The split is by severity, with the boundary between {@link Decision#WARN} and
 * {@link Decision#QUARANTINE}: a delivered verdict ({@code ALLOW}, or {@code WARN}
 * which delivers with a banner) is positive evidence for the sender; a withheld
 * verdict ({@code QUARANTINE} or {@code BLOCK}) is negative. A {@code switch} over
 * the enum with no {@code default} means a newly added tier fails to compile here
 * until its signal is chosen deliberately, never silently defaulting.
 */
public final class DecisionSignal {

    private DecisionSignal() {
    }

    /** The reputation signal a sender accrues for a message that earned {@code decision}. */
    public static ReputationSignal of(Decision decision) {
        return switch (decision) {
            case ALLOW, WARN -> ReputationSignal.GOOD;
            case QUARANTINE, BLOCK -> ReputationSignal.BAD;
        };
    }
}
