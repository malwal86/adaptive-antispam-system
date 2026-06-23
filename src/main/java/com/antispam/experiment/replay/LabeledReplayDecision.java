package com.antispam.experiment.replay;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * A replay verdict joined to the email's ground truth (story 09.04): the minimum the A/B harness
 * needs to score one policy on the fixed corpus. It pairs the {@link Decision} the replayed policy
 * reached and the {@link RouteUsed} that produced it with the corpus {@link GroundTruthLabel}, so a
 * single pass over a run's labeled decisions yields precision, recall, bypass, and the route mix.
 *
 * <p>Only emails that carry a ground-truth label appear here — the harness joins
 * {@code replay_decisions} to {@code ground_truth_labels} (story 11.01) and an unlabeled email
 * simply has no truth to score against, so it cannot contribute to precision/recall and is excluded
 * by the join rather than counted as a miss.
 *
 * @param emailId       the replayed email
 * @param decision      the tier the run's policy assigned (the verdict being graded)
 * @param route         the route that produced it — the cost/latency signal the harness aggregates
 * @param policyVersion the policy this run scored under (the same for every row of one run)
 * @param label         the corpus ground truth this verdict is graded against
 */
public record LabeledReplayDecision(
        UUID emailId, Decision decision, RouteUsed route, String policyVersion, GroundTruthLabel label) {
}
