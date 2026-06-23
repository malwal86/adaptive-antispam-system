package com.antispam.decision.policy;

/**
 * Which runtime signal tripped a burst escalation (Epic 06). Both are velocity over the same sliding
 * window and escalate the same way (the {@link com.antispam.decision.ReasonCode#BURST_OVERRIDE}
 * reason); the distinction is for observability — a flood keyed by sender vs. by content tells an
 * operator whether one sender is blasting or a templated campaign is arriving across many senders.
 */
public enum BurstTrigger {

    /** A single sender's message velocity exceeded the threshold (story 06.01). */
    SENDER_VELOCITY,

    /** A cluster of near-duplicate content exceeded the threshold across senders (story 06.02). */
    CONTENT_NEAR_DUP
}
