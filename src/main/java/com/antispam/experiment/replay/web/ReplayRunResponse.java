package com.antispam.experiment.replay.web;

import com.antispam.experiment.replay.ReplayRun;
import java.util.UUID;

/**
 * The {@code POST /replays} response: the run id to poll for results, the policy being scored, and
 * how many corpus emails were published to the replay topic.
 *
 * @param runId          tags every decision this run produces; poll {@code GET /replays/{runId}/decisions}
 * @param policyVersion  the policy each email is scored under
 * @param publishedCount how many corpus emails were published
 */
public record ReplayRunResponse(UUID runId, String policyVersion, int publishedCount) {

    public static ReplayRunResponse from(ReplayRun run) {
        return new ReplayRunResponse(run.runId(), run.policyVersion(), run.publishedCount());
    }
}
