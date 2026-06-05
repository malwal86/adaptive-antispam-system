package com.antispam.decision;

/**
 * Which stage of the pipeline produced a decision, recorded as {@code route_used}
 * on every {@link Classification}. Hard rules short-circuit at {@link #HARD_RULE},
 * skipping the model entirely; mail that no hard rule overrides is decided on the
 * {@link #MODEL} path. Later routes (LLM fallback, degraded) join with their epics.
 */
public enum RouteUsed {
    HARD_RULE,
    MODEL
}
