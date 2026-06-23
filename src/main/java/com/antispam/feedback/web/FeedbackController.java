package com.antispam.feedback.web;

import com.antispam.feedback.DecidedEmail;
import com.antispam.feedback.DecidedEmailRepository;
import com.antispam.feedback.FeedbackSimulationService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feedback-simulation endpoint (story 07.02). {@code POST /feedback/simulate} assembles a
 * persona population, streams the recently decided-and-labeled emails through it, and records the
 * sampled actions as a run — returning the run summary. It is logged-only: it writes
 * {@code feedback_events} and never touches the live decision path.
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private final DecidedEmailRepository decidedEmails;
    private final FeedbackSimulationService service;

    @Autowired
    public FeedbackController(DecidedEmailRepository decidedEmails, FeedbackSimulationService service) {
        this.decidedEmails = decidedEmails;
        this.service = service;
    }

    @PostMapping(path = "/simulate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FeedbackRunResponse simulate(@RequestBody FeedbackSimulationRequest request) {
        List<DecidedEmail> stream = decidedEmails.recentDecidedAndLabeled(request.limit());
        return FeedbackRunResponse.from(service.simulate(stream, request.toSpec()));
    }
}
