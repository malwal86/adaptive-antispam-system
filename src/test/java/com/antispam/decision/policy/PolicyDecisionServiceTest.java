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
import com.antispam.ingest.Email;
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
    private static final ModelScores SCORES = new ModelScores(0.4, 0.2, "bootstrap-v1", 0.6);

    private static final Policy LENIENT = policy("lenient-v1", 0.50, 0.80, 0.95);
    private static final Policy STRICT = policy("strict-v1", 0.20, 0.50, 0.70);

    private PolicyDecisionService service(BurstOverride burst) {
        return new PolicyDecisionService(policies, burst);
    }

    private static FusedScore posterior(double p) {
        return new FusedScore(p, 0.0, 0.0);
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

    private static DecisionOutcome modelOutcome() {
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 1L, SCORES);
    }

    private static BurstOverride noBurst() {
        return e -> Optional.empty();
    }

    private static Policy policy(String version, double warn, double quarantine, double block) {
        return new Policy(version, true, warn, quarantine, block, 0.40, "bootstrap-v1", Instant.EPOCH);
    }
}
