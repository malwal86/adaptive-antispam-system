package com.antispam.experiment.replay.web;

import com.antispam.experiment.replay.ReplayDecisionRepository;
import com.antispam.experiment.replay.ReplayService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The replay endpoints (story 09.01).
 *
 * <p>{@code POST /replays} triggers a replay: re-publish the immutable corpus to {@code emails.replay}
 * scored under a chosen policy, returning {@code 202 Accepted} with the run id — the experimental
 * consumer scores asynchronously. {@code GET /replays/{runId}/decisions} returns that run's recorded
 * verdicts, which are experiment-scoped and never confused with live classifications.
 */
@RestController
@RequestMapping("/replays")
public class ReplayController {

    private final ReplayService replayService;
    private final ReplayDecisionRepository decisions;

    @Autowired
    public ReplayController(ReplayService replayService, ReplayDecisionRepository decisions) {
        this.replayService = replayService;
        this.decisions = decisions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReplayRunResponse> start(@RequestBody StartReplayRequest request) {
        ReplayRunResponse response = ReplayRunResponse.from(
                replayService.startReplay(request.policyVersion()));
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping(path = "/{runId}/decisions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ReplayDecisionResponse> decisions(@PathVariable("runId") UUID runId) {
        return decisions.findByRunId(runId).stream().map(ReplayDecisionResponse::from).toList();
    }
}
