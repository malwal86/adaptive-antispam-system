package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.routing.RoutingReason;
import java.util.List;

/**
 * The verdict an email earns when scored under <em>a chosen</em> policy by
 * {@link PolicyScorer}, with no persistence and no enforcement attached. It is the
 * experiment-side analogue of {@link PolicyDecisionService.TieredDecision}: same tier,
 * reasons, route, and posterior, but produced for a policy passed in by the caller
 * (replay's selected policy, shadow's candidate policy, the arena's baseline) rather
 * than for the one active enforcing regime.
 *
 * <p>Because it carries the {@link #policyVersion} it was scored under, two scored
 * decisions for the same email under different policies are directly comparable — which
 * is exactly what shadow diffing (story 09.02) and replay A/B (story 09.04) consume.
 *
 * @param decision       the tier the policy assigns
 * @param reasonCodes    the codes justifying it (the route's own reasons); never null
 * @param route          the route that produced it, promoted to {@link RouteUsed#LLM} when
 *                       the routing predicates fire — but note the LLM is never actually
 *                       called on the experiment path (see {@link PolicyScorer})
 * @param routingReasons why it would escalate to the LLM; empty when it stays on the fast path
 * @param policyVersion  the policy this decision was scored under
 * @param posterior      the fused P(abuse), or {@code null} when the score was not fused
 *                       (a hard-rule verdict, or a model score with no calibration installed)
 */
public record ScoredDecision(
        Decision decision,
        List<ReasonCode> reasonCodes,
        RouteUsed route,
        List<RoutingReason> routingReasons,
        String policyVersion,
        Double posterior) {

    public ScoredDecision {
        reasonCodes = List.copyOf(reasonCodes);
        routingReasons = List.copyOf(routingReasons);
    }
}
