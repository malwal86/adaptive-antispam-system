package com.antispam.decision.llm;

import com.antispam.decision.Decision;

/**
 * The outcome of resolving a quarantine-pending email (story 05.06): the final {@link Decision}, the
 * {@link ResolutionState} that produced it, and whether the "running degraded" banner is raised.
 *
 * @param decision       the final tier the email is resolved to
 * @param state          how it was resolved (promoted / confirmed / degraded)
 * @param degradedBanner whether to surface the "running degraded" banner (true only on a degrade)
 */
public record ResolvedDecision(Decision decision, ResolutionState state, boolean degradedBanner) {

    public ResolvedDecision {
        if (decision == null || state == null) {
            throw new IllegalArgumentException("decision and state are required");
        }
        if (state == ResolutionState.PENDING) {
            throw new IllegalArgumentException("a resolved decision cannot still be PENDING");
        }
    }

    /** Whether this resolution delivers the message to the inbox (only a promotion does). */
    public boolean deliversToInbox() {
        return state.delivered();
    }
}
