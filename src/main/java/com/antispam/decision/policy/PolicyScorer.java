package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.DecisionService;
import com.antispam.decision.FusedScore;
import com.antispam.decision.FusionService;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.routing.LlmRouter;
import com.antispam.decision.routing.RoutingDecision;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Scores an email under <em>any</em> policy, read-only, with zero writes to live state. This is
 * the shared engine behind the three experiment paths that must compare regimes without enforcing
 * one: replay (story 09.01), live shadow scoring (09.02), and the adversarial arena (Epic 08).
 *
 * <p>{@link PolicyDecisionService} answers "what does the <em>active</em> policy decide, and record
 * it"; this answers "what would <em>this</em> policy decide, touching nothing." The two share the
 * same content model, calibration, fusion, tiering, and LLM-routing predicates — so an experimental
 * score is the genuine pipeline verdict, not a re-implementation that could drift — but this class
 * deliberately omits the parts of the live path that either write state or are non-deterministic:
 *
 * <ul>
 *   <li><b>No persistence.</b> It returns a {@link ScoredDecision} and has no repository at all, so
 *       it structurally cannot mint a {@code classifications} row (the isolation invariant of story
 *       09.03 is satisfied here by construction, not by discipline).</li>
 *   <li><b>No LLM call.</b> It evaluates the routing predicates and reports {@link RouteUsed#LLM}
 *       when they fire — the experiment learns whether the mail <em>would</em> escalate — but never
 *       spends the lever: an experiment must not incur per-call cost or import a non-deterministic
 *       provider verdict into a comparison.</li>
 *   <li><b>No burst override and no routing meter.</b> The burst override reads a time-windowed
 *       Redis counter (story 06.01): a live streaming signal that would make the same corpus score
 *       differently from one minute to the next, breaking the determinism replay relies on. The
 *       routing meter records live operational metrics, which an experiment must not perturb. Both
 *       are live-path-only concerns; the experimental score is the policy's deterministic verdict
 *       over content, reputation, and routing.</li>
 * </ul>
 *
 * <p><b>Determinism.</b> For a fixed email and policy the score is a pure function of the content
 * model, the installed calibration, and the sender's reputation as read at scoring time. Replayed
 * back-to-back (no new reputation accrues between runs) the same corpus under the same policy yields
 * an identical decision set — the determinism guarantee of story 09.01.
 */
@Service
public class PolicyScorer {

    private final DecisionService decisionService;
    private final FusionService fusionService;

    @Autowired
    public PolicyScorer(DecisionService decisionService, FusionService fusionService) {
        this.decisionService = decisionService;
        this.fusionService = fusionService;
    }

    /**
     * Scores {@code email} under {@code policy} without enforcing or persisting anything.
     *
     * @param email  the email to score
     * @param policy the regime to score it under (need not be the active one)
     * @return the tier, reasons, route, and posterior this policy assigns
     */
    public ScoredDecision score(Email email, Policy policy) {
        DecisionOutcome outcome = decisionService.evaluate(email);
        FusedScore fused = fuseIfApplicable(email, outcome);

        Decision tier = PolicyDecisionService.baseTier(policy, outcome, fused);

        RouteUsed route = outcome.route();
        List<RoutingReason> routingReasons = List.of();
        if (PolicyDecisionService.eligibleForRouting(outcome, fused)) {
            RoutingDecision routing = LlmRouter.decide(policy, fused, outcome.scores());
            if (routing.routed()) {
                route = RouteUsed.LLM;
                routingReasons = routing.reasons();
            }
        }

        Double posterior = fused == null ? null : fused.posterior();
        return new ScoredDecision(
                tier, List.<ReasonCode>copyOf(outcome.reasonCodes()), route, routingReasons,
                policy.version(), posterior);
    }

    /**
     * Fuses the model's calibrated score with sender reputation for a model-route decision, or
     * {@code null} when fusion does not apply — mirrors {@code DecisionService.fuseIfApplicable}:
     * a hard-rule decision carries no model score, and a model decision is fused only when a
     * calibration is installed.
     */
    private FusedScore fuseIfApplicable(Email email, DecisionOutcome outcome) {
        if (outcome.scores() == null) {
            return null;
        }
        return fusionService.fuse(email, outcome.scores()).orElse(null);
    }
}
