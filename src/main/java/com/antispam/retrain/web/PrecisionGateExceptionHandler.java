package com.antispam.retrain.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the gate's not-yet-gradeable case to an honest status rather than a 500: asking for the verdict
 * of a run whose decisions have not landed yet (or for which no golden eval set is materialized) is a
 * well-formed request against a run that is simply not comparable yet ({@link IllegalStateException}
 * from {@code PrecisionGateService.evaluate}) → {@code 409 Conflict}, so the polling retrain pipeline
 * knows to retry rather than treating it as a candidate failure.
 */
@RestControllerAdvice(assignableTypes = PrecisionGateController.class)
public class PrecisionGateExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNotYetGradeable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
