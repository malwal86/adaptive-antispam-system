package com.antispam.retrain.web;

import com.antispam.retrain.LabeledDataExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the labeled-data training export (story 10.01, the first step of the retrain loop).
 *
 * <p>{@code GET /retrain/export} returns the combined seed + weighted-feedback + arena training set —
 * each example with its label, weight, provenance, and feature version, and the golden eval set
 * excluded. It is a pure, deterministic read of the database (no side effects), so the scheduled CI
 * train step (10.02) can pull a reproducible training set the same way the deploy smoke tests curl the
 * other endpoints.
 */
@RestController
@RequestMapping("/retrain/export")
public class LabeledDataExportController {

    private final LabeledDataExportService service;
    private final ObjectMapper objectMapper;

    @Autowired
    public LabeledDataExportController(LabeledDataExportService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TrainingExportResponse export() {
        return TrainingExportResponse.from(service.export(), objectMapper);
    }
}
