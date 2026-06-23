package com.antispam.experiment.shadow;

import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.FusedScore;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.ExperimentContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Scores every live email under the configured shadow policy alongside the active one and records
 * the diff (story 09.02, PRD §Subsystem 8). It runs <b>off the request thread</b> and is wholly
 * logged-only: it enforces nothing, and a failure here can never affect the live verdict — the
 * "zero user impact" guarantee. When no shadow policy is configured (or it equals the active one),
 * it is a no-op, so the feature is off until an operator designates a candidate to shadow.
 *
 * <p><b>No model re-run, no bean cycle.</b> The live decision already computed the model output
 * ({@code outcome} + {@code fused}); both are policy-independent, so the shadow score is just a
 * second tiering of that output under the shadow policy via the static
 * {@link PolicyScorer#scoreFrom}. That is why this service depends on neither the decision pipeline
 * nor the {@link PolicyScorer} bean — only on the policy and shadow-decision repositories — which
 * keeps it free of the {@code DecisionService → ShadowScoringService} edge becoming a cycle.
 */
@Service
public class ShadowScoringService {

    private static final Logger log = LoggerFactory.getLogger(ShadowScoringService.class);

    private final PolicyRepository policies;
    private final ShadowDecisionRepository shadowDecisions;
    private final Executor executor;

    @Autowired
    public ShadowScoringService(
            PolicyRepository policies,
            ShadowDecisionRepository shadowDecisions,
            @Qualifier("shadowScoringExecutor") Executor executor) {
        this.policies = policies;
        this.shadowDecisions = shadowDecisions;
        this.executor = executor;
    }

    /**
     * Schedules shadow scoring of a just-decided email off the request thread. Returns immediately;
     * the live decision never waits on, and is never failed by, the shadow path.
     *
     * @param emailId the decided email
     * @param outcome the model output the live decision computed (route + scores)
     * @param fused   the fused posterior the live decision computed, or {@code null} if not fused
     */
    public void shadowScore(UUID emailId, DecisionOutcome outcome, FusedScore fused) {
        executor.execute(() -> {
            try {
                record(emailId, outcome, fused);
            } catch (RuntimeException e) {
                // Off the request thread: a shadow failure must be surfaced but never propagated —
                // the worst case is a missing evidence row, never a wrong or delayed live decision.
                log.error("shadow scoring failed for email={}", emailId, e);
            }
        });
    }

    /**
     * Scores the active and shadow policies from one model output and records the diff. No-op when
     * no shadow policy is configured, or when the shadow equals the active policy (no diff to learn).
     * Package-private so a unit test can drive it synchronously via a same-thread executor.
     */
    void record(UUID emailId, DecisionOutcome outcome, FusedScore fused) {
        // Read-only scope (story 09.03): the shadow path reads live policies and writes only
        // shadow_decisions; any stray write to live reputation/feedback/classifications underneath
        // is blocked at the repository, not left to discipline.
        ExperimentContext.runReadOnly(() -> {
            Optional<Policy> shadow = policies.findShadow();
            if (shadow.isEmpty()) {
                return;
            }
            Policy active = policies.findActive().orElseThrow(() -> new IllegalStateException(
                    "no active policy: shadow scoring compares against the enforcing regime"));
            if (active.version().equals(shadow.get().version())) {
                return;
            }

            ScoredDecision activeScore = PolicyScorer.scoreFrom(outcome, fused, active);
            ScoredDecision shadowScore = PolicyScorer.scoreFrom(outcome, fused, shadow.get());
            ShadowDiff diff = ShadowDiff.between(activeScore.decision(), shadowScore.decision());
            shadowDecisions.save(emailId, activeScore, shadowScore, diff);

            log.debug("shadow scored email={} active={}({}) shadow={}({}) agreement={} direction={}",
                    emailId, active.version(), activeScore.decision(),
                    shadow.get().version(), shadowScore.decision(), diff.agreement(), diff.direction());
        });
    }
}
