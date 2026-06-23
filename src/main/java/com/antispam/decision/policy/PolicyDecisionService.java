package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.FusedScore;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.BurstOverride.Escalation;
import com.antispam.decision.routing.LlmRouter;
import com.antispam.decision.routing.RoutingDecision;
import com.antispam.decision.routing.RoutingMeter;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The final tiering stage of the decision pipeline (story 04.05): it turns a fused
 * posterior into one of the four tiers using the <em>active</em> policy's thresholds, then
 * lets the burst-override hook escalate beyond what the score alone selected. It is the
 * step that makes decisioning policy-driven — switch the active {@link Policy} and the same
 * posterior yields a different tier, with no code change — which is the precondition for
 * shadow/replay comparison (Epic 09) and retrain promotion (Epic 10).
 *
 * <p><b>What it does and does not re-decide.</b> A hard-rule verdict is authoritative and
 * passes through untouched (it already skipped the model); only a model-route decision is
 * tiered from its posterior. A model decision that was never fused — no calibration is
 * installed (story 04.04) — keeps its provisional {@link Decision#ALLOW} rather than being
 * tiered against a score that does not exist. Every decision, whatever its route, is
 * stamped with the active {@code policy_version} so it is traceable to the regime that made
 * it.
 *
 * <p><b>Override precedence.</b> The burst override can only <em>raise</em> severity: the
 * final tier is the more severe of the posterior-derived tier and the override's floor, so
 * a weak override never softens a strong score. The {@link ReasonCode#BURST_OVERRIDE} reason
 * is recorded only when the override actually changed the tier.
 *
 * <p><b>LLM routing (story 05.01).</b> After tiering, a fused model decision is run through the
 * {@link LlmRouter} predicates; if it is uncertain enough the route is promoted to
 * {@link RouteUsed#LLM} and the firing {@link RoutingReason}s are recorded. The posterior-derived
 * tier still stands as the provisional verdict — the actual LLM call and quarantine-pending
 * resolution land in later stories (05.02, 05.06). A hard-rule verdict (authoritative) and an
 * unfused model row (no posterior to judge) are never routed.
 */
@Service
public class PolicyDecisionService {

    /**
     * The outcome of tiering: the final verdict, the reason codes justifying it (the
     * route's own reasons plus any override reason), the route that produced it, the routing
     * reasons (empty unless escalated to the LLM), and the policy that produced it.
     *
     * @param decision       the final tier
     * @param reasonCodes    the codes justifying the verdict (may be empty)
     * @param route          the final route, promoted to {@link RouteUsed#LLM} when escalated
     * @param routingReasons why it was escalated to the LLM; empty when it stayed on the fast path
     * @param policyVersion  the active policy this decision was made under
     */
    public record TieredDecision(
            Decision decision,
            List<ReasonCode> reasonCodes,
            RouteUsed route,
            List<RoutingReason> routingReasons,
            String policyVersion) {

        public TieredDecision {
            reasonCodes = List.copyOf(reasonCodes);
            routingReasons = List.copyOf(routingReasons);
        }
    }

    private final PolicyRepository policies;
    private final BurstOverride burstOverride;
    private final RoutingMeter routingMeter;

    @Autowired
    public PolicyDecisionService(
            PolicyRepository policies, BurstOverride burstOverride, RoutingMeter routingMeter) {
        this.policies = policies;
        this.burstOverride = burstOverride;
        this.routingMeter = routingMeter;
    }

    /**
     * Derives the final tier and route for {@code email} from its decision and fused posterior
     * under the active policy: the posterior-derived tier (with any burst-override escalation),
     * then the LLM-routing predicates that may promote the route to {@link RouteUsed#LLM}.
     *
     * @param outcome the route's verdict (hard-rule, authoritative; or model, provisional)
     * @param fused   the fused posterior, or {@code null} when the model score was not fused
     * @return the final tiered decision stamped with the active policy version
     * @throws IllegalStateException if no policy is active
     */
    public TieredDecision decide(Email email, DecisionOutcome outcome, FusedScore fused) {
        Policy active = policies.findActive().orElseThrow(() -> new IllegalStateException(
                "no active policy: decisioning is policy-driven and needs one enforcing regime"));

        Decision tier = baseTier(active, outcome, fused);
        List<ReasonCode> reasons = new ArrayList<>(outcome.reasonCodes());

        Optional<Escalation> escalation = burstOverride.evaluate(email, active);
        if (escalation.isPresent()) {
            Decision escalated = Decision.mostSevere(List.of(tier, escalation.get().tier()));
            if (escalated != tier) {
                tier = escalated;
                reasons.add(escalation.get().reason());
            }
        }

        RouteUsed route = outcome.route();
        List<RoutingReason> routingReasons = List.of();
        if (eligibleForRouting(outcome, fused)) {
            RoutingDecision routing = LlmRouter.decide(active, fused, outcome.scores());
            if (routing.routed()) {
                route = RouteUsed.LLM;
                routingReasons = routing.reasons();
            }
        }
        recordRouting(route, routingReasons);

        return new TieredDecision(tier, reasons, route, routingReasons, active.version());
    }

    /**
     * Whether {@code outcome} can be escalated to the LLM: only a fused model decision is. A
     * hard-rule verdict is authoritative and skipped the model, so it never escalates; an unfused
     * model row (no calibration installed) has no posterior for the predicates to judge.
     *
     * <p>Package-private (not private) because {@link PolicyScorer} reuses the same eligibility
     * rule to score an arbitrary policy off the live path — the routing predicate must be applied
     * identically whether the policy is the active one or an experimental one.
     */
    static boolean eligibleForRouting(DecisionOutcome outcome, FusedScore fused) {
        return outcome.route() == RouteUsed.MODEL && fused != null;
    }

    private void recordRouting(RouteUsed route, List<RoutingReason> routingReasons) {
        if (route == RouteUsed.LLM) {
            routingMeter.recordRouted(routingReasons);
        } else {
            routingMeter.recordFastPath();
        }
    }

    /**
     * The tier {@code policy} assigns before any override: a hard-rule verdict as decided, a fused
     * model score mapped through the policy thresholds, or — when the model score was not fused —
     * the model route's provisional verdict left as-is.
     *
     * <p>Package-private (not private) because {@link PolicyScorer} reuses it verbatim to tier an
     * arbitrary policy off the live path; the passthrough-vs-tier rule is shared knowledge that
     * must stay identical between the enforced decision and an experimental score.
     */
    static Decision baseTier(Policy policy, DecisionOutcome outcome, FusedScore fused) {
        if (outcome.route() == RouteUsed.HARD_RULE || fused == null) {
            return outcome.decision();
        }
        return policy.tierFor(fused.posterior());
    }
}
