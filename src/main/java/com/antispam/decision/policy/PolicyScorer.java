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
     * Scores {@code email} under {@code policy} without enforcing or persisting anything: runs the
     * content model and reputation fusion (the policy-independent part), then tiers and routes the
     * result under {@code policy} (the policy-dependent part).
     *
     * @param email  the email to score
     * @param policy the regime to score it under (need not be the active one)
     * @return the tier, reasons, route, and posterior this policy assigns
     */
    public ScoredDecision score(Email email, Policy policy) {
        DecisionOutcome outcome = decisionService.evaluate(email);
        FusedScore fused = fusionService.fuseIfApplicable(email, outcome);
        return scoreFrom(outcome, fused, policy);
    }

    /**
     * Tiers and routes an already-computed model output under {@code policy} — the policy-dependent
     * tail of {@link #score}, factored out as a pure static function so a caller that already has
     * the model output can score a second policy from it for free (no model re-run). Shadow scoring
     * (story 09.02) uses this to score the active and shadow policies from one evaluation, and it is
     * the seam that keeps shadow free of any dependency on the live decision pipeline (so there is
     * no {@code DecisionService → ShadowScoringService → PolicyScorer → DecisionService} bean cycle).
     *
     * <p>The model scores and the fused posterior are policy-independent — fusion takes reputation,
     * calibration, and the training base rate, never a policy threshold — so only the tier and the
     * LLM-routing decision differ between two policies scored from the same {@code outcome}/{@code fused}.
     *
     * @param outcome the route's verdict and scores (hard-rule, or model)
     * @param fused   the fused posterior, or {@code null} when the model score was not fused
     * @param policy  the regime to tier and route under
     * @return the tier, reasons, route, and posterior {@code policy} assigns
     */
    public static ScoredDecision scoreFrom(DecisionOutcome outcome, FusedScore fused, Policy policy) {
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
}
