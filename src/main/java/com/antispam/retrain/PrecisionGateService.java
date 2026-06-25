package com.antispam.retrain;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.experiment.replay.LabeledReplayDecision;
import com.antispam.experiment.replay.PolicyMetrics;
import com.antispam.experiment.replay.ReplayDecisionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Runs the precision-floor promotion gate over a completed candidate replay (story 10.03): it grades
 * the candidate's decisions on the frozen golden set and hands the resulting scorecard to
 * {@link PrecisionFloorGate} for the pass/fail verdict. It is the deterministic-replay half of the
 * gate (Epic 09 path); the floor logic itself is the pure {@link PrecisionFloorGate}.
 *
 * <p>"On the golden set" is the load-bearing detail: the candidate is replayed over the whole corpus
 * (the existing {@code ReplayService} path), but only the held-out eval side is graded
 * ({@link ReplayDecisionRepository#findLabeledByRunIdInGoldenSet}). Grading a retrain candidate on
 * mail it could have trained on would inflate its precision and defeat the gate, so the scope is
 * narrowed at grading time rather than asking the replay to know about the split.
 *
 * <p>Because the replay is deterministic and the grading is a pure function of the stored decisions,
 * re-running the gate on the same candidate run yields an identical verdict (AC 5).
 */
@Service
public class PrecisionGateService {

    private static final Logger log = LoggerFactory.getLogger(PrecisionGateService.class);

    private final ReplayDecisionRepository decisions;
    private final PolicyRepository policies;
    private final PrecisionFloorGate gate;

    @Autowired
    public PrecisionGateService(
            ReplayDecisionRepository decisions, PolicyRepository policies, PrecisionFloorGate gate) {
        this.decisions = decisions;
        this.policies = policies;
        this.gate = gate;
    }

    /**
     * Grades a completed candidate replay on the golden set and applies the precision floor.
     *
     * @param candidateRunId the replay run that scored the candidate over the corpus
     * @return the gate verdict — pass/fail plus the precision, floor, and reported evidence
     * @throws IllegalStateException if the run has no graded golden decisions yet (not consumed, or
     *         no golden set exists) — the caller should poll until the run has landed
     */
    public GateResult evaluate(UUID candidateRunId) {
        List<LabeledReplayDecision> golden = decisions.findLabeledByRunIdInGoldenSet(candidateRunId);
        return grade(candidateRunId, "live eval side", golden);
    }

    /**
     * Grades a completed candidate replay against a FROZEN golden version and applies the precision
     * floor (story 11.02). Identical to {@link #evaluate(UUID)} except the held-out set is the immutable
     * snapshot named by {@code goldenVersion} — so the same candidate, re-graded against the same
     * version after a future retrain, is measured on byte-identical emails and the precision is
     * comparable across model versions.
     *
     * @param candidateRunId the replay run that scored the candidate over the corpus
     * @param goldenVersion  the frozen golden version to grade against
     * @return the gate verdict — pass/fail plus the precision, floor, and reported evidence
     * @throws IllegalStateException if the run has no graded decisions in that version yet
     */
    public GateResult evaluate(UUID candidateRunId, String goldenVersion) {
        List<LabeledReplayDecision> golden =
                decisions.findLabeledByRunIdInGoldenVersion(candidateRunId, goldenVersion);
        return grade(candidateRunId, "golden version " + goldenVersion, golden);
    }

    /** Resolves the candidate's model version onto the verdict and applies the floor. */
    private GateResult grade(UUID candidateRunId, String basis, List<LabeledReplayDecision> golden) {
        if (golden.isEmpty()) {
            throw new IllegalStateException(
                    "no golden-set decisions for replay run " + candidateRunId + " on " + basis
                            + " (run not yet consumed, or no golden eval set is materialized)");
        }

        // Every row of one run shares the policy it scored under; resolve the model it is calibrated
        // for so the verdict records the artifact actually being promoted (AC 1).
        String policyVersion = golden.get(0).policyVersion();
        String modelVersion = policies.findByVersion(policyVersion).map(Policy::modelVersion).orElse(null);

        GateResult result = gate.evaluate(PolicyMetrics.of(policyVersion, modelVersion, golden));
        log.info("precision gate run={} basis='{}' policy={} model={} precision={} floor={} passed={} ({})",
                candidateRunId, basis, policyVersion, modelVersion, result.precision(),
                result.precisionFloor(), result.passed(), result.reason());
        return result;
    }
}
