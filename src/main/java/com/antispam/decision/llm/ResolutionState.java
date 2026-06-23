package com.antispam.decision.llm;

/**
 * The lifecycle of an uncertain email's decision (story 05.06; PRD §Live decision timing). The
 * uncertain ~5% the router escalates are not delivered on the fast path — they are provisionally
 * <em>withheld</em> ({@link #PENDING}) and then resolved by the LLM within a bounded SLA, to one of
 * three terminal states. The states encode the central invariant: a message is delivered to the
 * inbox <em>only</em> by an explicit {@link #PROMOTED} transition out of withholding, so the user is
 * never shown a message that is later retracted ("never deliver-then-retract").
 */
public enum ResolutionState {

    /** Provisionally withheld (quarantine-pending), awaiting the async LLM resolution. */
    PENDING(false),

    /** The LLM judged the message legitimate: promoted out of withholding and delivered. */
    PROMOTED(true),

    /** The LLM confirmed the message abusive: it stays withheld (quarantine or block). */
    CONFIRMED(false),

    /**
     * The LLM could not resolve within the SLA, or the budget was spent: the system fail-degrades to
     * the fast-path posterior with a conservative (withholding) bias and raises the "running
     * degraded" banner. Distinct from fail-open (would deliver) and fail-closed (would hard-block).
     */
    DEGRADED(false);

    private final boolean delivered;

    ResolutionState(boolean delivered) {
        this.delivered = delivered;
    }

    /** Whether reaching this state delivers the message to the inbox. Only {@link #PROMOTED} does. */
    public boolean delivered() {
        return delivered;
    }
}
