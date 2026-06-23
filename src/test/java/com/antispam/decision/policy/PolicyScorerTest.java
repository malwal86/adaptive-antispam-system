package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.DecisionService;
import com.antispam.decision.FusedScore;
import com.antispam.decision.FusionService;
import com.antispam.decision.ModelScores;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.TestEmails;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The read-only scoring engine's contract (story 09.01): it scores an email under whatever policy
 * it is handed — not the active one — so switching the policy changes the tier on a fixed email;
 * it is deterministic; and it reports the route the routing predicates select without ever calling
 * the LLM (the engine has no LLM collaborator to call). These are the properties replay (09.01),
 * shadow (09.02), and the arena (Epic 08) all build on.
 */
@ExtendWith(MockitoExtension.class)
class PolicyScorerTest {

    @Mock
    private DecisionService decisionService;

    @Mock
    private FusionService fusionService;

    private final Email email = TestEmails.bodyContaining("hello");

    // Confident scores (calibrated 0.97) so the tiering tests exercise tiering alone; the routing
    // test below picks low-confidence scores that trip a predicate.
    private static final ModelScores CONFIDENT = new ModelScores(0.9, 0.07, "bootstrap-v1", 0.97);

    private static final Policy LENIENT = policy("lenient-v1", 0.50, 0.80, 0.95);
    private static final Policy STRICT = policy("strict-v1", 0.20, 0.50, 0.70);

    private PolicyScorer scorer() {
        return new PolicyScorer(decisionService, fusionService);
    }

    /** A fused score far from every boundary with no sender uncertainty (no routing). */
    private static FusedScore posterior(double p) {
        return new FusedScore(p, 0.0, 0.0, 0.0);
    }

    @Test
    void tiers_the_posterior_under_the_policy_it_is_handed_not_the_active_one() {
        when(decisionService.evaluate(email)).thenReturn(modelOutcome());
        when(fusionService.fuse(any(), any())).thenReturn(Optional.of(posterior(0.60)));

        ScoredDecision scored = scorer().score(email, LENIENT);

        assertThat(scored.decision()).isEqualTo(Decision.WARN);
        assertThat(scored.policyVersion()).isEqualTo("lenient-v1");
        assertThat(scored.posterior()).isEqualTo(0.60);
    }

    @Test
    void switching_the_policy_changes_the_tier_for_a_fixed_email() {
        when(decisionService.evaluate(email)).thenReturn(modelOutcome());
        when(fusionService.fuse(any(), any())).thenReturn(Optional.of(posterior(0.60)));

        Decision underLenient = scorer().score(email, LENIENT).decision();
        Decision underStrict = scorer().score(email, STRICT).decision();

        assertThat(underLenient).isEqualTo(Decision.WARN);       // 0.60 ≥ warn 0.50, < quarantine 0.80
        assertThat(underStrict).isEqualTo(Decision.QUARANTINE);  // 0.60 ≥ quarantine 0.50, < block 0.70
    }

    @Test
    void scores_the_same_email_and_policy_identically_each_time() {
        when(decisionService.evaluate(email)).thenReturn(modelOutcome());
        when(fusionService.fuse(any(), any())).thenReturn(Optional.of(posterior(0.60)));

        ScoredDecision first = scorer().score(email, LENIENT);
        ScoredDecision second = scorer().score(email, LENIENT);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void passes_a_hard_rule_verdict_through_untouched_but_stamps_the_policy() {
        DecisionOutcome hardRule = new DecisionOutcome(
                Decision.BLOCK, List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 2L);
        when(decisionService.evaluate(email)).thenReturn(hardRule);

        ScoredDecision scored = scorer().score(email, LENIENT);

        assertThat(scored.decision()).isEqualTo(Decision.BLOCK);
        assertThat(scored.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
        assertThat(scored.route()).isEqualTo(RouteUsed.HARD_RULE);
        assertThat(scored.policyVersion()).isEqualTo("lenient-v1");
        assertThat(scored.posterior()).isNull();
    }

    @Test
    void leaves_a_model_decision_provisional_when_the_score_was_not_fused() {
        when(decisionService.evaluate(email)).thenReturn(modelOutcome());
        // No calibration installed → fusion declines.
        when(fusionService.fuse(any(), any())).thenReturn(Optional.empty());

        ScoredDecision scored = scorer().score(email, LENIENT);

        assertThat(scored.decision()).isEqualTo(Decision.ALLOW);
        assertThat(scored.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(scored.posterior()).isNull();
    }

    @Test
    void reports_the_llm_route_when_a_predicate_fires_without_calling_any_llm() {
        // Low calibrated confidence (0.55 → model confidence 0.10 < llmThreshold 0.40); posterior
        // 0.65 is a clear WARN far from every boundary with no sender uncertainty.
        DecisionOutcome lowConfidence = modelOutcome(new ModelScores(0.4, 0.2, "bootstrap-v1", 0.55));
        when(decisionService.evaluate(email)).thenReturn(lowConfidence);
        when(fusionService.fuse(any(), any())).thenReturn(Optional.of(posterior(0.65)));

        ScoredDecision scored = scorer().score(email, LENIENT);

        assertThat(scored.route()).isEqualTo(RouteUsed.LLM);
        assertThat(scored.routingReasons()).containsExactly(RoutingReason.LOW_MODEL_CONFIDENCE);
        // The posterior-derived tier still stands; the scorer never asked an LLM for a verdict.
        assertThat(scored.decision()).isEqualTo(Decision.WARN);
    }

    @Test
    void keeps_a_confident_mid_tier_decision_on_the_fast_path() {
        when(decisionService.evaluate(email)).thenReturn(modelOutcome());
        when(fusionService.fuse(any(), any())).thenReturn(Optional.of(posterior(0.65)));

        ScoredDecision scored = scorer().score(email, LENIENT);

        assertThat(scored.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(scored.routingReasons()).isEmpty();
    }

    private static DecisionOutcome modelOutcome() {
        return modelOutcome(CONFIDENT);
    }

    private static DecisionOutcome modelOutcome(ModelScores scores) {
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 1L, scores);
    }

    private static Policy policy(String version, double warn, double quarantine, double block) {
        return new Policy(
                version, true, warn, quarantine, block, 0.40, 0.05, 20, "bootstrap-v1", Instant.EPOCH);
    }
}
