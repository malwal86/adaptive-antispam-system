package com.antispam.decision;

import com.antispam.decision.hardrule.HardRuleCircuitBreaker;
import com.antispam.decision.hardrule.HardRuleEngine;
import com.antispam.decision.llm.LlmFallbackService;
import com.antispam.decision.llm.LlmOutcome;
import com.antispam.decision.llm.LlmProperties;
import com.antispam.decision.llm.QuarantinePendingService;
import com.antispam.decision.policy.PolicyDecisionService;
import com.antispam.experiment.shadow.ShadowScoringService;
import com.antispam.ingest.Email;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The fast-path decision pipeline for a single email (story 01.04 stage). It runs
 * the hard rules first and, only if none override, falls through to the model
 * path; the resulting decision is persisted.
 *
 * <p>The short-circuit is structural, not incidental: {@code orElseGet} evaluates
 * the {@link ContentClassifier} lazily, so a hard-rule hit reaches a decision
 * without the model ever being invoked — the explicit "skip the model" guarantee
 * from PRD §Subsystem 1. Later stages (burst override, LLM routing) extend this
 * pipeline in their epics.
 *
 * <p><b>Fusion is a persist-time stage</b> (story 04.04). {@link #evaluate} stops at the
 * model's calibrated score, because the read-only consumers that call it (reputation
 * accrual off the spine, story 03.05) only need the verdict, not a reputation-fused
 * posterior — and fusing there would make accrual read reputation to score reputation.
 * {@link #decide} runs the extra step: for a model-route decision it fuses the calibrated
 * score with the sender's reputation prior ({@link FusionService}) and records the
 * posterior on the row. Fusion is skipped (and no posterior stored) when it is not
 * applicable — a hard-rule row, or a model row scored before any calibration is installed.
 *
 * <p><b>Deciding vs. persisting are separable.</b> {@link #evaluate} runs the core
 * pipeline and returns the in-memory {@link DecisionOutcome} without writing a row;
 * {@link #decide} is {@code evaluate} plus fusion plus persistence. The split lets a
 * read-only consumer derive a verdict for an email without minting a second
 * {@link Classification} for it.
 *
 * <p><b>LLM fallback is a persist-time stage too</b> (story 05.02). When the policy stage routes
 * a decision to the LLM, {@link #decide} calls the
 * {@link com.antispam.decision.llm.LlmFallbackService} for a validated verdict (or a degrade) and
 * records its cost and latency on the row. The verdict does not yet change the tier — that
 * resolution, and the async quarantine-pending SLA, are story 05.06.
 *
 * <p><b>The hard-rule circuit breaker floors the final decision</b> (story 05.05). Before the row
 * is written, {@link HardRuleCircuitBreaker} guarantees the decision is never softer than the
 * hard-rule verdict, so an LLM verdict can escalate an ambiguous email but can never downgrade a
 * hard-rule block/quarantine to allow.
 *
 * <p><b>Quarantine-pending when the LLM is enabled</b> (story 05.06). With the LLM on, an
 * LLM-routed (uncertain) email is not decided synchronously: {@link #decide} withholds it
 * (quarantine-pending) within the &lt;100ms budget and hands it to
 * {@link com.antispam.decision.llm.QuarantinePendingService}, which resolves it asynchronously
 * within the SLA — promote, confirm, or fail-degrade — never deliver-then-retract. With the LLM
 * disabled (local/dev/tests) there is no provider to wait for, so the inline call simply
 * fail-degrades on the fast-path posterior, exactly as in story 05.02.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final HardRuleEngine hardRuleEngine;
    private final HardRuleCircuitBreaker circuitBreaker;
    private final ContentClassifier contentClassifier;
    private final ClassificationRepository repository;
    private final FusionService fusionService;
    private final PolicyDecisionService policyDecisionService;
    private final LlmFallbackService llmFallbackService;
    private final LlmProperties llmProperties;
    private final QuarantinePendingService quarantinePendingService;
    private final ShadowScoringService shadowScoringService;

    @Autowired
    public DecisionService(
            HardRuleEngine hardRuleEngine,
            HardRuleCircuitBreaker circuitBreaker,
            ContentClassifier contentClassifier,
            ClassificationRepository repository,
            FusionService fusionService,
            PolicyDecisionService policyDecisionService,
            LlmFallbackService llmFallbackService,
            LlmProperties llmProperties,
            QuarantinePendingService quarantinePendingService,
            ShadowScoringService shadowScoringService) {
        this.hardRuleEngine = hardRuleEngine;
        this.circuitBreaker = circuitBreaker;
        this.contentClassifier = contentClassifier;
        this.repository = repository;
        this.fusionService = fusionService;
        this.policyDecisionService = policyDecisionService;
        this.llmFallbackService = llmFallbackService;
        this.llmProperties = llmProperties;
        this.quarantinePendingService = quarantinePendingService;
        this.shadowScoringService = shadowScoringService;
    }

    /**
     * Runs the decision pipeline for {@code email} and returns the verdict
     * <em>without persisting it</em>: hard rules first, the model path only if none
     * override. Deterministic for a given email, so a caller can derive the verdict
     * more than once (e.g. on redelivery) and get the same answer.
     *
     * @return the in-memory {@link DecisionOutcome}
     */
    public DecisionOutcome evaluate(Email email) {
        return hardRuleEngine.evaluate(email)
                .orElseGet(() -> contentClassifier.classify(email));
    }

    /**
     * Decides {@code email} end-to-end: evaluate the route, fuse the model score with
     * sender reputation where applicable, derive the final tier from the active policy
     * (with the burst-override hook), and record the decision.
     *
     * @return the persisted {@link Classification}
     */
    public Classification decide(Email email) {
        DecisionOutcome outcome = evaluate(email);
        FusedScore fused = fuseIfApplicable(email, outcome);

        // Live shadow scoring (story 09.02): score the same model output under the configured shadow
        // policy and record the diff, off the request thread. Logged-only and isolated — it never
        // enforces and never fails the live decision — so it sits before the enforced tiering and
        // adds no latency. A no-op when no shadow policy is configured.
        shadowScoringService.shadowScore(email.id(), outcome, fused);

        PolicyDecisionService.TieredDecision tiered = policyDecisionService.decide(email, outcome, fused);

        // Hard-rule circuit breaker floor (story 05.05): the final decision can never be softer than
        // what the hard rules demand — the hard-rule verdict when one fired, else ALLOW (which floors
        // nothing). Routed mail came off the model route, so its floor is ALLOW; the floor matters
        // for the quarantine-pending resolution, which the LLM verdict can otherwise move.
        Decision hardRuleFloor =
                outcome.route() == RouteUsed.HARD_RULE ? outcome.decision() : Decision.ALLOW;

        // Quarantine-pending path (story 05.06): when the router escalated the decision (route == LLM)
        // and the LLM is actually enabled, the uncertain mail is provisionally WITHHELD and resolved
        // asynchronously within the SLA — promote-to-inbox, confirm-spam, or fail-degrade — never
        // deliver-then-retract. No inline LLM call, so the synchronous decision stays within <100ms.
        // With the LLM disabled (local/dev/tests, no provider) there is nothing to wait for, so the
        // call below fail-degrades inline on the fast-path posterior, exactly as before this story.
        if (tiered.route() == RouteUsed.LLM && llmProperties.enabled()) {
            QuarantinePendingService.ResolutionRequest request =
                    new QuarantinePendingService.ResolutionRequest(
                            email, tiered.routingReasons(), outcome.scores(), fused,
                            tiered.policyVersion(), tiered.decision(), hardRuleFloor, outcome.latencyMs());
            Classification pending = quarantinePendingService.beginResolution(request);
            log.info("quarantine-pending email={} route={} provisional={} routingReasons={} policy={}",
                    email.id(), RouteUsed.LLM, pending.decision(), tiered.routingReasons(),
                    tiered.policyVersion());
            return pending;
        }

        // Fast path (and the LLM-disabled fallback): call the LLM inline only to fail-degrade when
        // it is routed but disabled (story 05.02); the provisional posterior-derived tier stands.
        LlmOutcome llm = tiered.route() == RouteUsed.LLM
                ? llmFallbackService.classify(email, tiered.routingReasons())
                : null;
        long latencyMs = outcome.latencyMs() + (llm == null ? 0L : llm.latencyMs());
        BigDecimal llmCostUsd = llm == null ? null : llm.costUsd();

        Decision finalDecision =
                circuitBreaker.floorAtHardRule(email.id(), hardRuleFloor, tiered.decision());

        // Re-stamp the route's verdict with the policy-derived tier, reasons, and route — the route
        // is the tiering stage's, since LLM routing (story 05.01) may have promoted it past the
        // route the classifier established. The latency now includes the LLM call when one was made.
        DecisionOutcome finalOutcome = new DecisionOutcome(
                finalDecision, tiered.reasonCodes(), tiered.route(), latencyMs, outcome.scores());
        Classification classification =
                repository.save(email.id(), finalOutcome, fused, tiered.policyVersion(), llmCostUsd);
        // No PII here: only the email id, route, verdict, reason codes, posterior, policy, and the
        // LLM's categorical verdict/cost (never its free-text explanation, which echoes content).
        log.info("decided email={} route={} decision={} reasons={} routingReasons={} latencyMs={} "
                        + "posterior={} policy={} llmVerdict={} llmDegraded={} llmCostUsd={}",
                email.id(), finalOutcome.route(), finalOutcome.decision(),
                finalOutcome.reasonCodes(), tiered.routingReasons(), finalOutcome.latencyMs(),
                fused == null ? null : fused.posterior(), tiered.policyVersion(),
                llm == null || llm.degraded() ? null : llm.verdict().verdict(),
                llm == null ? null : llm.degraded(), llmCostUsd);
        return classification;
    }

    /**
     * Fuses the model's calibrated score with sender reputation for a model-route
     * decision, or {@code null} when fusion does not apply: a hard-rule decision carries
     * no model score, and a model decision is fused only if a calibration is installed
     * (fusion requires a calibrated probability — story 04.04 AC 3).
     */
    private FusedScore fuseIfApplicable(Email email, DecisionOutcome outcome) {
        if (outcome.scores() == null) {
            return null;
        }
        return fusionService.fuse(email, outcome.scores()).orElse(null);
    }
}
