package com.antispam.experiment.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.FusedScore;
import com.antispam.decision.ModelScores;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.shadow.ShadowDiff.Agreement;
import com.antispam.experiment.shadow.ShadowDiff.Direction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The shadow scorer's contract (story 09.02): with a shadow policy configured it scores the active
 * and shadow policies from one model output and records their diff; it is a no-op when no shadow is
 * configured or the shadow equals the active policy; and a recording failure is isolated (never
 * propagated to the caller, i.e. the live decision). Driven with a same-thread executor so the
 * async work is observable inline.
 */
@ExtendWith(MockitoExtension.class)
class ShadowScoringServiceTest {

    @Mock
    private PolicyRepository policies;

    @Mock
    private ShadowDecisionRepository shadowDecisions;

    private ShadowScoringService service() {
        return new ShadowScoringService(policies, shadowDecisions, Runnable::run);
    }

    private static final UUID EMAIL_ID = UUID.randomUUID();
    // A confident model output with a fused posterior of 0.60.
    private static final ModelScores SCORES = new ModelScores(0.55, 0.05, "bootstrap-v1", 0.97);
    private static final DecisionOutcome OUTCOME =
            new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 1L, SCORES);
    private static final FusedScore FUSED = new FusedScore(0.60, 0.0, 0.0, 0.0);

    private static final Policy LENIENT = policy("lenient-v1", 0.50, 0.80, 0.95);
    private static final Policy STRICT = policy("strict-v1", 0.20, 0.50, 0.70);

    @Test
    void records_the_active_vs_shadow_diff_when_a_shadow_policy_is_configured() {
        // active LENIENT (0.60 → WARN), shadow STRICT (0.60 → QUARANTINE): a more-severe disagreement.
        when(policies.findShadow()).thenReturn(Optional.of(STRICT));
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));

        service().shadowScore(EMAIL_ID, OUTCOME, FUSED);

        ArgumentCaptor<ScoredDecision> active = ArgumentCaptor.forClass(ScoredDecision.class);
        ArgumentCaptor<ScoredDecision> shadow = ArgumentCaptor.forClass(ScoredDecision.class);
        ArgumentCaptor<ShadowDiff> diff = ArgumentCaptor.forClass(ShadowDiff.class);
        verify(shadowDecisions).save(eq(EMAIL_ID), active.capture(), shadow.capture(), diff.capture());

        assertThat(active.getValue().decision()).isEqualTo(Decision.WARN);
        assertThat(active.getValue().policyVersion()).isEqualTo("lenient-v1");
        assertThat(shadow.getValue().decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(shadow.getValue().policyVersion()).isEqualTo("strict-v1");
        assertThat(diff.getValue().agreement()).isEqualTo(Agreement.DISAGREE);
        assertThat(diff.getValue().direction()).isEqualTo(Direction.SHADOW_MORE_SEVERE);
    }

    @Test
    void is_a_no_op_when_no_shadow_policy_is_configured() {
        when(policies.findShadow()).thenReturn(Optional.empty());

        service().shadowScore(EMAIL_ID, OUTCOME, FUSED);

        verify(shadowDecisions, never()).save(any(), any(), any(), any());
    }

    @Test
    void is_a_no_op_when_the_shadow_equals_the_active_policy() {
        when(policies.findShadow()).thenReturn(Optional.of(LENIENT));
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));

        service().shadowScore(EMAIL_ID, OUTCOME, FUSED);

        verify(shadowDecisions, never()).save(any(), any(), any(), any());
    }

    @Test
    void isolates_a_recording_failure_from_the_caller() {
        when(policies.findShadow()).thenReturn(Optional.of(STRICT));
        when(policies.findActive()).thenReturn(Optional.of(LENIENT));
        when(shadowDecisions.save(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        // The live decision thread must not see the shadow failure.
        service().shadowScore(EMAIL_ID, OUTCOME, FUSED);
    }

    private static Policy policy(String version, double warn, double quarantine, double block) {
        return new Policy(
                version, true, warn, quarantine, block, 0.40, 0.05, 20, "bootstrap-v1", Instant.EPOCH);
    }
}
