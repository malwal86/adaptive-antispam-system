package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.FusedScore;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.BurstOverride.Escalation;
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
 */
@Service
public class PolicyDecisionService {

    /**
     * The outcome of tiering: the final verdict, the reason codes justifying it (the
     * route's own reasons plus any override reason), and the policy that produced it.
     *
     * @param decision     the final tier
     * @param reasonCodes  the codes justifying it (may be empty)
     * @param policyVersion the active policy this decision was made under
     */
    public record TieredDecision(Decision decision, List<ReasonCode> reasonCodes, String policyVersion) {

        public TieredDecision {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    private final PolicyRepository policies;
    private final BurstOverride burstOverride;

    @Autowired
    public PolicyDecisionService(PolicyRepository policies, BurstOverride burstOverride) {
        this.policies = policies;
        this.burstOverride = burstOverride;
    }

    /**
     * Derives the final tier for {@code email} from its decision and fused posterior under
     * the active policy.
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

        Optional<Escalation> escalation = burstOverride.evaluate(email);
        if (escalation.isPresent()) {
            Decision escalated = Decision.mostSevere(List.of(tier, escalation.get().tier()));
            if (escalated != tier) {
                tier = escalated;
                reasons.add(escalation.get().reason());
            }
        }

        return new TieredDecision(tier, reasons, active.version());
    }

    /**
     * The tier before any override: a hard-rule verdict as decided, a fused model score
     * mapped through the policy thresholds, or — when the model score was not fused — the
     * model route's provisional verdict left as-is.
     */
    private static Decision baseTier(Policy active, DecisionOutcome outcome, FusedScore fused) {
        if (outcome.route() == RouteUsed.HARD_RULE || fused == null) {
            return outcome.decision();
        }
        return active.tierFor(fused.posterior());
    }
}
