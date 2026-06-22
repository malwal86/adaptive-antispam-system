package com.antispam.decision.routing;

import com.antispam.decision.FusedScore;
import com.antispam.decision.ModelScores;
import com.antispam.decision.policy.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The LLM-routing predicates (story 05.01, PRD §Subsystem 1 step 5): pure logic that decides
 * whether a fused model decision is uncertain enough to spend the expensive lever on. A decision
 * routes if <em>any</em> of three predicates fires — low model confidence, high sender
 * (reputation) uncertainty, or a posterior near a tier boundary — so the ~5% that genuinely need
 * the LLM escalate and the ~95% stay on the cheap fast path (PRD §Live timing).
 *
 * <p>All thresholds come from the active {@link Policy}, never hardcoded, so the routed fraction
 * is tuned by changing the regime (the same lever Epics 09/10 use). Two knobs drive the
 * predicates: {@link Policy#llmThreshold()} is the confidence floor, and
 * {@link Policy#routingBandWidth()} is the boundary band half-width.
 *
 * <p><b>Beta variance widens the band.</b> The sender's reputation uncertainty —
 * {@link FusedScore#uncertaintyBand()}, propagated from the Beta variance — both fires the
 * new-sender predicate on its own <em>and</em> widens the boundary-proximity band, because that
 * variance is largest exactly where the conditional-independence approximation behind the fusion
 * is weakest (PRD §Subsystem 2). A static utility, mirroring {@link com.antispam.decision.LogOddsFusion}:
 * the routing rule is a pure function of the policy and the scores, with no state of its own.
 */
public final class LlmRouter {

    private LlmRouter() {
    }

    /**
     * Evaluates the routing predicates for a fused model decision.
     *
     * @param policy the active regime supplying the routing thresholds
     * @param fused  the reputation-fused posterior and its uncertainty band
     * @param scores the model's scores, for the calibrated-confidence predicate
     * @return the reasons that fired (empty when the fast path decides the email)
     */
    public static RoutingDecision decide(Policy policy, FusedScore fused, ModelScores scores) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(fused, "fused");
        Objects.requireNonNull(scores, "scores");

        List<RoutingReason> reasons = new ArrayList<>(3);

        if (modelConfidence(scores.calibratedConfidence()) < policy.llmThreshold()) {
            reasons.add(RoutingReason.LOW_MODEL_CONFIDENCE);
        }
        if (fused.uncertaintyBand() >= policy.routingBandWidth()) {
            reasons.add(RoutingReason.NEW_SENDER_UNCERTAINTY);
        }
        if (nearTierBoundary(policy, fused)) {
            reasons.add(RoutingReason.NEAR_TIER_BOUNDARY);
        }

        return new RoutingDecision(reasons);
    }

    /**
     * The model's decisiveness in {@code [0,1]}: {@code 0} at the maximally-uncertain midpoint
     * {@code 0.5}, rising to {@code 1} as the calibrated probability approaches a confident
     * {@code 0} or {@code 1}. "Low confidence" is this quantity falling below the policy floor.
     */
    private static double modelConfidence(double calibratedConfidence) {
        return 2.0 * Math.abs(calibratedConfidence - 0.5);
    }

    /**
     * Whether the posterior is within the (variance-widened) routing band of any tier boundary.
     * The effective half-width is the policy's fixed band plus the sender's uncertainty band, so a
     * shaky prior widens the zone in which a boundary call is escalated.
     */
    private static boolean nearTierBoundary(Policy policy, FusedScore fused) {
        double effectiveBand = policy.routingBandWidth() + fused.uncertaintyBand();
        double posterior = fused.posterior();
        double nearest = Math.min(
                Math.abs(posterior - policy.warnThreshold()),
                Math.min(
                        Math.abs(posterior - policy.quarantineThreshold()),
                        Math.abs(posterior - policy.blockThreshold())));
        return nearest <= effectiveBand;
    }
}
