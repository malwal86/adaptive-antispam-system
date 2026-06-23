package com.antispam.experiment.replay;

import java.util.UUID;

/**
 * The summary a replay trigger returns (story 09.01): the {@code runId} that tags every decision
 * the run will produce, the policy it is scoring under, and how many corpus emails were published
 * to {@code emails.replay}. The caller polls {@code replay_decisions} by {@code runId} to read the
 * results as the experimental consumer scores them.
 *
 * @param runId          the identifier shared by every decision of this replay
 * @param policyVersion  the policy each email will be scored under
 * @param publishedCount how many corpus emails were published to the replay topic
 */
public record ReplayRun(UUID runId, String policyVersion, int publishedCount) {
}
