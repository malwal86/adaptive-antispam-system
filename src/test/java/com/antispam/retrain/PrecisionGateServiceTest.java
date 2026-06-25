package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.experiment.replay.LabeledReplayDecision;
import com.antispam.experiment.replay.ReplayDecisionRepository;
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
 * The gate service's grading contract (story 10.03), pinned with mocked repositories so it needs no
 * database. It proves the two things the service adds over the pure {@link PrecisionFloorGate}: it
 * grades on the <b>golden (eval-side) set</b> — never the whole-corpus join, which would let a
 * candidate be judged on mail it could have trained on — and it resolves the candidate's model
 * version onto the verdict. The pass/fail arithmetic itself lives in {@link PrecisionFloorGateTest}.
 */
@ExtendWith(MockitoExtension.class)
class PrecisionGateServiceTest {

    private static final UUID RUN = UUID.randomUUID();

    @Mock
    private ReplayDecisionRepository decisions;

    @Mock
    private PolicyRepository policies;

    private PrecisionGateService service() {
        // A floor of 0.0 and a single-sample minimum so the tiny fixed fixture is judged by the
        // service's wiring, not by the floor — the floor itself is exercised in the gate unit test.
        PrecisionFloorGate gate = new PrecisionFloorGate(new PrecisionGateProperties(0.0, 1));
        return new PrecisionGateService(decisions, policies, gate);
    }

    @Test
    void grades_on_the_golden_set_and_records_the_candidates_model_version() {
        when(decisions.findLabeledByRunIdInGoldenSet(RUN)).thenReturn(List.of(
                golden(GroundTruthLabel.SPAM, Decision.BLOCK),
                golden(GroundTruthLabel.HAM, Decision.ALLOW)));
        when(policies.findByVersion("cand")).thenReturn(Optional.of(candidatePolicy("model-7")));

        GateResult result = service().evaluate(RUN);

        assertThat(result.policyVersion()).isEqualTo("cand");
        assertThat(result.modelVersion()).isEqualTo("model-7");
        assertThat(result.goldenSampleCount()).isEqualTo(2);
        // One withheld spam, no withheld ham → precision 1.0; clears the 0.0 floor.
        assertThat(result.passed()).isTrue();
        assertThat(result.precision()).isEqualTo(1.0);
    }

    @Test
    void throws_when_the_run_has_no_golden_decisions_yet() {
        when(decisions.findLabeledByRunIdInGoldenSet(RUN)).thenReturn(List.of());

        assertThatThrownBy(() -> service().evaluate(RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RUN.toString());
    }

    @Test
    void grades_against_a_frozen_golden_version_when_one_is_named() {
        // The version-comparable path (story 11.02): the candidate is graded on the immutable snapshot
        // named by the version, not the live eval side.
        when(decisions.findLabeledByRunIdInGoldenVersion(RUN, "golden-1")).thenReturn(List.of(
                golden(GroundTruthLabel.SPAM, Decision.BLOCK),
                golden(GroundTruthLabel.HAM, Decision.ALLOW)));
        when(policies.findByVersion("cand")).thenReturn(Optional.of(candidatePolicy("model-7")));

        GateResult result = service().evaluate(RUN, "golden-1");

        assertThat(result.modelVersion()).isEqualTo("model-7");
        assertThat(result.goldenSampleCount()).isEqualTo(2);
        assertThat(result.precision()).isEqualTo(1.0);
    }

    @Test
    void throws_when_the_named_golden_version_has_no_decisions_yet() {
        when(decisions.findLabeledByRunIdInGoldenVersion(RUN, "golden-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service().evaluate(RUN, "golden-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("golden-1");
    }

    private static LabeledReplayDecision golden(GroundTruthLabel label, Decision decision) {
        return new LabeledReplayDecision(
                UUID.randomUUID(), decision, RouteUsed.MODEL, "cand", label);
    }

    private static Policy candidatePolicy(String modelVersion) {
        return new Policy("cand", false, 0.30, 0.60, 0.80, 0.40, 0.10, 5, modelVersion, Instant.now());
    }
}
