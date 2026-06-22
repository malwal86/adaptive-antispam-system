package com.antispam.decision.calibration.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a calibration precondition failure to a clear HTTP response. Asking to calibrate
 * before the corpus is seeded and split — too few held-out samples to measure honestly —
 * is a conflict with the system's current state, not a server fault, so it becomes a 409
 * telling the operator to seed and rebuild the split first.
 */
@RestControllerAdvice(assignableTypes = ModelCalibrationController.class)
public class CalibrationExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNotReady(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
