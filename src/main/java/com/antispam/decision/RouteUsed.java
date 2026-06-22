package com.antispam.decision;

/**
 * Which stage of the pipeline produced a decision, recorded as {@code route_used}
 * on every {@link Classification}. Hard rules short-circuit at {@link #HARD_RULE},
 * skipping the model entirely; mail that no hard rule overrides is decided on the
 * {@link #MODEL} path. A model decision the routing predicates flag as uncertain is
 * escalated to {@link #LLM} (story 05.01). Later routes (degraded) join with their epics.
 */
public enum RouteUsed {
    HARD_RULE,
    MODEL,

    /**
     * The model decision was uncertain enough to escalate to the LLM (story 05.01). The fused
     * posterior still stamps the row's provisional tier; the actual LLM call and its
     * quarantine-pending resolution land in later stories (05.02, 05.06).
     */
    LLM
}
