package com.antispam.feedback.sensitivity.web;

import com.antispam.feedback.sensitivity.FeedbackSensitivityService;
import com.antispam.feedback.sensitivity.SensitivityReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sensitivity-sweep endpoint (story 07.04). {@code POST /feedback/sensitivity} runs the
 * malicious-fraction sweep and returns the {@link SensitivityReport} — the per-fraction curve plus
 * the observed and analytical breakdown points — the chart/table the demo surfaces (AC 3/AC 4). An
 * empty body runs the default sweep.
 *
 * <p>The report is returned directly: it is already the flat summary the demo needs, so a separate
 * DTO would only add a pass-through layer.
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackSensitivityController {

    private final FeedbackSensitivityService service;

    @Autowired
    public FeedbackSensitivityController(FeedbackSensitivityService service) {
        this.service = service;
    }

    @PostMapping(path = "/sensitivity", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SensitivityReport sweep(@RequestBody(required = false) SweepRequest request) {
        SweepRequest effective = request != null ? request : new SweepRequest(null, null, null, null);
        return service.sweep(effective.toSpec());
    }
}
