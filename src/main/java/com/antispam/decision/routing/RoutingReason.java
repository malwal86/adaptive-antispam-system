package com.antispam.decision.routing;

/**
 * Why the pipeline escalated an email to the LLM (story 05.01, PRD §Subsystem 1 step 5). A
 * decision routes to the LLM if <em>any</em> of these fire; the set of those that did is the
 * audit trail for the escalation and the {@code reason} tag on the routing metric, so the
 * routed fraction can be attributed to its cause rather than seen only in aggregate.
 *
 * <p>These are distinct from {@link com.antispam.decision.ReasonCode}: a reason code justifies a
 * <em>verdict</em>, whereas a routing reason justifies <em>spending the expensive lever</em>.
 * Keeping them separate stops "why did we escalate" from contaminating "why did we block".
 */
public enum RoutingReason {

    /**
     * The model's own calibrated confidence is low — its probability sits near the
     * maximally-uncertain midpoint, so the cheap classifier alone is not trusted.
     */
    LOW_MODEL_CONFIDENCE,

    /**
     * The sender carries high reputation uncertainty (wide Beta variance, typically a new
     * sender), so the fused posterior rests on a shaky prior (PRD §Subsystem 2: the Beta
     * variance is largest exactly where the conditional-independence approximation is weakest).
     */
    NEW_SENDER_UNCERTAINTY,

    /**
     * The fused posterior sits within the routing band of a tier boundary — close enough that a
     * small error could flip the verdict to an adjacent tier — so the boundary call is escalated.
     */
    NEAR_TIER_BOUNDARY
}
