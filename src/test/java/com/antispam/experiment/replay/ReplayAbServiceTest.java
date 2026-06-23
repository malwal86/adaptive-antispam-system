package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The A/B harness's orchestration contract (story 09.04): it validates both policies before
 * publishing either run (both-or-neither), triggers one replay per policy through the real replay
 * path, and grades two completed runs into a {@code B − A} comparison tagged by policy and model
 * version. A run that has not landed yet is reported as not-yet-comparable, not as empty metrics.
 */
@ExtendWith(MockitoExtension.class)
class ReplayAbServiceTest {

    @Mock
    private ReplayService replayService;

    @Mock
    private ReplayDecisionRepository decisions;

    @Mock
    private PolicyRepository policies;

    private ReplayAbService service() {
        return new ReplayAbService(replayService, decisions, policies);
    }

    private static Policy policy(String version, String modelVersion) {
        return new Policy(version, false, 0.5, 0.7, 0.9, 0.6, 0.1, 5, modelVersion, Instant.now());
    }

    private static LabeledReplayDecision labeled(
            Decision decision, RouteUsed route, String policyVersion, GroundTruthLabel label) {
        return new LabeledReplayDecision(UUID.randomUUID(), decision, route, policyVersion, label);
    }

    @Test
    void starts_one_replay_per_policy_and_returns_both_run_ids() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        when(policies.findByVersion("base-v1")).thenReturn(Optional.of(policy("base-v1", "m6")));
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.of(policy("cand-v2", "m7")));
        when(replayService.startReplay("base-v1")).thenReturn(new ReplayRun(runA, "base-v1", 42));
        when(replayService.startReplay("cand-v2")).thenReturn(new ReplayRun(runB, "cand-v2", 42));

        ReplayAbRun ab = service().startAb("base-v1", "cand-v2");

        assertThat(ab.runIdA()).isEqualTo(runA);
        assertThat(ab.policyVersionA()).isEqualTo("base-v1");
        assertThat(ab.runIdB()).isEqualTo(runB);
        assertThat(ab.policyVersionB()).isEqualTo("cand-v2");
        assertThat(ab.publishedCount()).isEqualTo(42);
        verify(replayService).startReplay("base-v1");
        verify(replayService).startReplay("cand-v2");
    }

    @Test
    void rejects_an_unknown_baseline_policy_before_publishing_anything() {
        when(policies.findByVersion("ghost")).thenReturn(Optional.empty());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service().startAb("ghost", "cand-v2"))
                .withMessageContaining("ghost");

        // Both-or-neither: no run is published when a policy is unknown.
        verifyNoInteractions(replayService);
    }

    @Test
    void rejects_an_unknown_candidate_without_publishing_the_baseline_run() {
        when(policies.findByVersion("base-v1")).thenReturn(Optional.of(policy("base-v1", "m6")));
        when(policies.findByVersion("ghost")).thenReturn(Optional.empty());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service().startAb("base-v1", "ghost"))
                .withMessageContaining("ghost");

        verify(replayService, never()).startReplay("base-v1");
    }

    @Test
    void compares_two_runs_into_a_report_tagged_by_policy_and_model_version() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        when(decisions.findLabeledByRunId(runA)).thenReturn(List.of(
                labeled(Decision.ALLOW, RouteUsed.MODEL, "base-v1", GroundTruthLabel.SPAM)));
        when(decisions.findLabeledByRunId(runB)).thenReturn(List.of(
                labeled(Decision.BLOCK, RouteUsed.MODEL, "cand-v2", GroundTruthLabel.SPAM)));
        when(policies.findByVersion("base-v1")).thenReturn(Optional.of(policy("base-v1", "m6")));
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.of(policy("cand-v2", "m7")));

        ComparisonReport report = service().compare(runA, runB);

        assertThat(report.policyA().policyVersion()).isEqualTo("base-v1");
        assertThat(report.policyA().modelVersion()).isEqualTo("m6");
        assertThat(report.policyB().policyVersion()).isEqualTo("cand-v2");
        assertThat(report.policyB().modelVersion()).isEqualTo("m7");
        // A let the spam through; B caught it.
        assertThat(report.policyA().recall()).isZero();
        assertThat(report.policyB().recall()).isEqualTo(1.0);
        assertThat(report.deltas().recall()).isEqualTo(1.0);
    }

    @Test
    void comparing_a_run_with_no_landed_decisions_is_not_yet_comparable() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        when(decisions.findLabeledByRunId(runA)).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> service().compare(runA, runB))
                .withMessageContaining(runA.toString());
    }
}
