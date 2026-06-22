package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.FusedScore;
import com.antispam.decision.ModelScores;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.TestEmails;
import com.antispam.decision.policy.PolicyDecisionService.TieredDecision;
import com.antispam.decision.routing.RoutingMeter;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The tiering stage's contract (story 04.05): the final tier comes from the active
 * policy's thresholds applied to the posterior, the choice is policy-driven (switching the
 * active policy changes the tier on a fixed posterior), the burst-override hook can
 * escalate beyond the score, and every decision records the active {@code policy_version}.
 */
@ExtendWith(MockitoExtension.class)
class PolicyDecisionServiceTest {

    @Mock
    private PolicyRepository policies;

    private final Email email = TestEmails.bodyContaining("hello");
    // Confident scores (calibrated 0.97 → high model confidence) so the existing tiering tests
    // exercise tiering alone; the routing-specific tests below pick scores that trigger routing.
    private static final ModelScores SCORES = new ModelScores(0.9, 0.07, "bootstrap-v1", 0.97);

    private static final Policy LENIENT = policy("lenient-v1", 0.50, 0.80, 0.95);
    private static final Policy STRICT = policy("strict-v1", 0.20, 0.50, 0.70);

    private final RoutingMeter routingMeter = new RoutingMeter(new SimpleMeterRegistry());

    private PolicyDecisionService service(BurstOverride burst) {
        return new PolicyDecisionService(policies, burst, routingMeter);
    }

    /** A fused score far from every LENIENT boundary with no sender uncertainty (no routing). */
    private static FusedScore posterior(double p) {
        return new FusedScore(p, 0.0, 0.0, 0.0);
    }

    @Test
    void maps_the_posterior_to_a_tier_via_the_active_policy() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));

        TieredDecision tiered = service(noBurst()).decide(email, modelOutcome(), posterior(0.60));

        assertThat(tiered.decision()).isEqualTo(Decision.WARN);
        assertThat(tiered.policyVersion()).isEqualTo("lenient-v1");
        assertThat(tiered.reasonCodes()).isEmpty();
    }

    @Test
    void switching_the_active_policy_changes_the_tier_for_a_fixed_posterior() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT), Optional.of(STRICT));

        Decision underLenient = service(noBurst()).decide(email, modelOutcome(), posterior(0.60)).decision();
        Decision underStrict = service(noBurst()).decide(email, modelOutcome(), posterior(0.60)).decision();

        assertThat(underLenient).isEqualTo(Decision.WARN);
        assertThat(underStrict).isEqualTo(Decision.QUARANTINE);
    }

    @Test
    void passes_a_hard_rule_verdict_through_untouched_but_stamps_the_policy() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        DecisionOutcome hardRule = new DecisionOutcome(
                Decision.BLOCK, List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 2L);

        TieredDecision tiered = service(noBurst()).decide(email, hardRule, null);

        assertThat(tiered.decision()).isEqualTo(Decision.BLOCK);
        assertThat(tiered.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
        assertThat(tiered.policyVersion()).isEqualTo("lenient-v1");
    }

    @Test
    void leaves_a_model_decision_provisional_when_the_score_was_not_fused() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));

        // No fused posterior (uncalibrated model): the provisional ALLOW from the model
        // route stands rather than being tiered against a score that does not exist.
        TieredDecision tiered = service(noBurst()).decide(email, modelOutcome(), null);

        assertThat(tiered.decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void burst_override_escalates_beyond_the_posterior_tier_and_records_the_reason() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        BurstOverride burst = e -> Optional.of(
                new BurstOverride.Escalation(Decision.QUARANTINE, ReasonCode.BURST_OVERRIDE));

        // Posterior 0.10 maps to ALLOW, but the burst override forces at least QUARANTINE.
        TieredDecision tiered = service(burst).decide(email, modelOutcome(), posterior(0.10));

        assertThat(tiered.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(tiered.reasonCodes()).contains(ReasonCode.BURST_OVERRIDE);
    }

    @Test
    void burst_override_never_lowers_an_already_more_severe_tier() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        BurstOverride burst = e -> Optional.of(
                new BurstOverride.Escalation(Decision.WARN, ReasonCode.BURST_OVERRIDE));

        // Posterior 0.99 already maps to BLOCK; a weaker escalation must not soften it,
        // and since it did not actually escalate, no override reason is added.
        TieredDecision tiered = service(burst).decide(email, modelOutcome(), posterior(0.99));

        assertThat(tiered.decision()).isEqualTo(Decision.BLOCK);
        assertThat(tiered.reasonCodes()).doesNotContain(ReasonCode.BURST_OVERRIDE);
    }

    @Test
    void fails_loudly_when_no_policy_is_active() {
        when(policies.findActive()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(noBurst()).decide(email, modelOutcome(), posterior(0.6)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void escalates_an_uncertain_model_decision_to_the_llm_keeping_its_provisional_tier() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        // Low calibrated confidence (0.55 → model confidence 0.10 < llmThreshold 0.40); posterior
        // 0.65 is a clear WARN, far from every boundary, with no sender uncertainty.
        DecisionOutcome lowConfidence = modelOutcome(new ModelScores(0.4, 0.2, "bootstrap-v1", 0.55));

        TieredDecision tiered = service(noBurst()).decide(email, lowConfidence, posterior(0.65));

        assertThat(tiered.route()).isEqualTo(RouteUsed.LLM);
        assertThat(tiered.routingReasons()).containsExactly(RoutingReason.LOW_MODEL_CONFIDENCE);
        // The posterior-derived tier still stands as the provisional verdict (no LLM call yet).
        assertThat(tiered.decision()).isEqualTo(Decision.WARN);
    }

    @Test
    void keeps_a_confident_low_uncertainty_mid_tier_decision_on_the_fast_path() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));

        // Confident SCORES (0.97), posterior 0.65 far from boundaries, no sender uncertainty.
        TieredDecision tiered = service(noBurst()).decide(email, modelOutcome(), posterior(0.65));

        assertThat(tiered.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(tiered.routingReasons()).isEmpty();
    }

    @Test
    void never_routes_a_hard_rule_verdict_to_the_llm() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        DecisionOutcome hardRule = new DecisionOutcome(
                Decision.BLOCK, List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 2L);

        TieredDecision tiered = service(noBurst()).decide(email, hardRule, null);

        assertThat(tiered.route()).isEqualTo(RouteUsed.HARD_RULE);
        assertThat(tiered.routingReasons()).isEmpty();
    }

    @Test
    void never_routes_an_unfused_model_decision_with_no_posterior_to_judge() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));

        // No fused posterior (uncalibrated model): there is nothing for the predicates to judge.
        TieredDecision tiered = service(noBurst()).decide(email, modelOutcome(), null);

        assertThat(tiered.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(tiered.routingReasons()).isEmpty();
    }

    @Test
    void records_the_routed_and_fast_path_decisions_as_a_metric() {
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PolicyDecisionService service =
                new PolicyDecisionService(policies, noBurst(), new RoutingMeter(registry));
        DecisionOutcome lowConfidence = modelOutcome(new ModelScores(0.4, 0.2, "bootstrap-v1", 0.55));

        service.decide(email, lowConfidence, posterior(0.65));        // routed (low confidence)
        service.decide(email, modelOutcome(), posterior(0.65));       // confident → fast path

        assertThat(registry.get("antispam.decision.routing").tag("routed", "true").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("antispam.decision.routing").tag("routed", "false").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("antispam.decision.routing.reason")
                .tag("reason", RoutingReason.LOW_MODEL_CONFIDENCE.name()).counter().count())
                .isEqualTo(1.0);
    }

    private static DecisionOutcome modelOutcome() {
        return modelOutcome(SCORES);
    }

    private static DecisionOutcome modelOutcome(ModelScores scores) {
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 1L, scores);
    }

    private static BurstOverride noBurst() {
        return e -> Optional.empty();
    }

    private static Policy policy(String version, double warn, double quarantine, double block) {
        return new Policy(version, true, warn, quarantine, block, 0.40, 0.05, "bootstrap-v1", Instant.EPOCH);
    }
}
