package com.antispam.experiment.replay;

import com.antispam.decision.policy.ScoredDecision;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored row of a replay (story 09.01): the {@link ScoredDecision} an email earned under the
 * run's chosen policy, tagged with the {@code runId} that groups the replay and the {@code emailId}
 * it concerns. It is the experiment-scoped analogue of a {@code Classification} — same verdict
 * shape, but explicitly never an enforced live decision (AC 5).
 *
 * @param id        the row's generated identifier
 * @param runId     the replay this decision belongs to
 * @param emailId   the replayed email
 * @param scored    the verdict the run's policy assigned (carries the policy version and posterior)
 * @param createdAt when the row was written
 */
public record ReplayDecision(UUID id, UUID runId, UUID emailId, ScoredDecision scored, Instant createdAt) {
}
