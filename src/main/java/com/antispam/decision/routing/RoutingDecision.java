package com.antispam.decision.routing;

import java.util.List;

/**
 * The outcome of evaluating the LLM-routing predicates for one email (story 05.01): the set of
 * {@link RoutingReason}s that fired, in the order the predicates are evaluated. An email is
 * {@link #routed()} to the LLM exactly when at least one predicate fired — the OR-of-predicates
 * from PRD §Subsystem 1 step 5. An empty set means the fast path decides the email.
 *
 * @param reasons the predicates that fired; empty (never null) when none did
 */
public record RoutingDecision(List<RoutingReason> reasons) {

    public RoutingDecision {
        // Defensive immutable copy; rejects a null list / null elements loudly at construction.
        reasons = List.copyOf(reasons);
    }

    /** Whether this email escalates to the LLM (any predicate fired). */
    public boolean routed() {
        return !reasons.isEmpty();
    }
}
