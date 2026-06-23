package com.antispam.feedback.gate.web;

import com.antispam.feedback.gate.FeedbackGateService;
import com.antispam.feedback.gate.GateOutcome;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feedback-gate endpoint (story 07.03). {@code POST /feedback/gate/{runId}} runs a recorded
 * simulation run's {@code feedback_events} through the weighting/corroboration gate and returns what
 * it did — how many groups it considered, how many it trusted vs blocked, and how many rows reached
 * the reputation and retrain-label sinks. It is the one path that lets feedback move state, and it
 * is idempotent: re-posting the same run does not double-count (it reports zero fresh emissions).
 *
 * <p>The {@link GateOutcome} is returned directly: it is already the flat, self-describing summary
 * the API needs, so wrapping it in a separate DTO would only add a pass-through layer.
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackGateController {

    private final FeedbackGateService service;

    @Autowired
    public FeedbackGateController(FeedbackGateService service) {
        this.service = service;
    }

    @PostMapping(path = "/gate/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public GateOutcome gate(@PathVariable("runId") UUID runId) {
        return service.gate(runId);
    }
}
