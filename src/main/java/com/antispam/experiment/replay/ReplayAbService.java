package com.antispam.experiment.replay;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The replay A/B experiment harness (story 09.04): runs one fixed corpus through policy A and policy
 * B and turns the two runs' graded decisions into a controlled comparison the promotion gate (Epic
 * 10) consumes. It is the offline counterpart to live shadow scoring (story 09.02) — same fixed
 * corpus through two regimes, but deterministic and isolated rather than live.
 *
 * <p>It owns no scoring of its own: it triggers two ordinary replays via {@link ReplayService} (the
 * real {@code emails.replay} Kafka path, isolated and side-effect-free by stories 09.01/09.03), then
 * grades each run's stored decisions against ground truth into a {@link PolicyMetrics} scorecard and
 * pairs them into a {@link ComparisonReport}. Because replay is deterministic and the grading is a
 * pure function of the stored decisions, re-running the same corpus through the same two policies
 * yields identical metrics (AC 3) — the property the precision-floor gate relies on.
 */
@Service
public class ReplayAbService {

    private static final Logger log = LoggerFactory.getLogger(ReplayAbService.class);

    private final ReplayService replayService;
    private final ReplayDecisionRepository decisions;
    private final PolicyRepository policies;

    @Autowired
    public ReplayAbService(
            ReplayService replayService, ReplayDecisionRepository decisions, PolicyRepository policies) {
        this.replayService = replayService;
        this.decisions = decisions;
        this.policies = policies;
    }

    /**
     * Starts an A/B: validates both policies, then triggers a replay of the whole corpus under each.
     * Both policies are validated up front so the harness publishes both runs or neither — a started
     * baseline with no candidate (because B was a typo) would be a misleading orphan.
     *
     * @param policyVersionA the baseline (control) policy
     * @param policyVersionB the candidate (challenger) policy
     * @return the two run ids to poll, plus the corpus size published to each
     * @throws IllegalArgumentException if either policy version is unknown
     */
    public ReplayAbRun startAb(String policyVersionA, String policyVersionB) {
        requirePolicy(policyVersionA);
        requirePolicy(policyVersionB);

        ReplayRun runA = replayService.startReplay(policyVersionA);
        ReplayRun runB = replayService.startReplay(policyVersionB);

        log.info("replay A/B started runA={} policyA={} runB={} policyB={} corpus={}",
                runA.runId(), policyVersionA, runB.runId(), policyVersionB, runA.publishedCount());
        return new ReplayAbRun(
                runA.runId(), policyVersionA, runB.runId(), policyVersionB, runA.publishedCount());
    }

    /**
     * Grades two completed runs into a comparison: each run's decisions scored against ground truth
     * into a {@link PolicyMetrics} scorecard, paired with {@code A} as baseline and {@code B} as
     * candidate so every delta reads {@code B − A}.
     *
     * @param runIdA the baseline (control) run
     * @param runIdB the candidate (challenger) run
     * @return the paired scorecards and their per-metric deltas
     * @throws IllegalStateException if either run has no labeled decisions yet (not consumed, or an
     *         unlabeled corpus) — the caller should poll until both runs have landed
     */
    public ComparisonReport compare(UUID runIdA, UUID runIdB) {
        return ComparisonReport.of(metricsForRun(runIdA), metricsForRun(runIdB));
    }

    private void requirePolicy(String policyVersion) {
        if (policies.findByVersion(policyVersion).isEmpty()) {
            throw new IllegalArgumentException("no policy with version " + policyVersion);
        }
    }

    private PolicyMetrics metricsForRun(UUID runId) {
        List<LabeledReplayDecision> labeled = decisions.findLabeledByRunId(runId);
        if (labeled.isEmpty()) {
            throw new IllegalStateException(
                    "no labeled decisions for replay run " + runId
                            + " (run not yet consumed, or the corpus carries no ground-truth labels)");
        }
        // Every row of one run shares the policy it was scored under; resolve the model version it is
        // calibrated for so the scorecard is version-comparable (AC 2).
        String policyVersion = labeled.get(0).policyVersion();
        String modelVersion = policies.findByVersion(policyVersion).map(Policy::modelVersion).orElse(null);
        return PolicyMetrics.of(policyVersion, modelVersion, labeled);
    }
}
