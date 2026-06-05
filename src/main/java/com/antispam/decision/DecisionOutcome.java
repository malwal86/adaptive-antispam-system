package com.antispam.decision;

import java.util.List;

/**
 * A decision the pipeline has reached but not yet persisted: the verdict, the
 * reason codes that justify it, the route that produced it, and how long that
 * route took to decide.
 *
 * <p>Produced either by the {@link com.antispam.decision.hardrule.HardRuleEngine}
 * (route {@link RouteUsed#HARD_RULE}) or by the {@link ContentClassifier}
 * (route {@link RouteUsed#MODEL}); {@link DecisionService} persists it into a
 * {@link Classification}. Separating the in-memory outcome from the stored row
 * keeps the deciding logic free of any persistence concern.
 *
 * @param decision    the verdict tier
 * @param reasonCodes the codes justifying it, in rule-evaluation order; may be
 *                    empty (e.g. a plain allow) but never null
 * @param route       the pipeline stage that produced this outcome
 * @param latencyMs   wall-clock milliseconds that route spent deciding
 */
public record DecisionOutcome(
        Decision decision,
        List<ReasonCode> reasonCodes,
        RouteUsed route,
        long latencyMs) {

    public DecisionOutcome {
        // Defensive immutable copy so a caller can't mutate the codes after the
        // fact (and to reject a null list / null elements loudly at construction).
        reasonCodes = List.copyOf(reasonCodes);
    }
}
