package com.antispam.decision.calibration.web;

import com.antispam.decision.calibration.ModelCalibrationService;
import com.antispam.decision.calibration.ModelCalibrationService.CalibrationRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fits and inspects the served model's probability calibration (story 04.02).
 *
 * <p>{@code POST /model/calibration} runs a calibration — fit on the split's train side,
 * reliability measured on the held-out eval side — persists the evidence, and installs the
 * calibrator on the serving path if it clears the gate. The response is the report either
 * way; {@code passed} says whether it was installed. {@code GET /model/calibration} returns
 * the latest report for the served model, the standing evidence that its confidence is a
 * true probability, or 404 if no calibration has been run yet.
 */
@RestController
@RequestMapping("/model/calibration")
public class ModelCalibrationController {

    private final ModelCalibrationService service;

    @Autowired
    public ModelCalibrationController(ModelCalibrationService service) {
        this.service = service;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public CalibrationReportResponse calibrate() {
        CalibrationRun run = service.calibrate();
        return CalibrationReportResponse.from(run.stored());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CalibrationReportResponse> current() {
        return service.currentReport()
                .map(CalibrationReportResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
