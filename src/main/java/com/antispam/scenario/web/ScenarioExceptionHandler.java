package com.antispam.scenario.web;

import com.antispam.common.ApiErrors;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the scenario endpoint's refusals to honest statuses rather than 500s: an unknown scenario name
 * ({@link IllegalArgumentException}) is a bad request → 400; a start that collides with a scenario
 * already running ({@link IllegalStateException}) is a state conflict → 409.
 */
@RestControllerAdvice(assignableTypes = ScenarioController.class)
public class ScenarioExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleUnknownScenario(IllegalArgumentException e) {
        return ApiErrors.body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleAlreadyRunning(IllegalStateException e) {
        return ApiErrors.body(HttpStatus.CONFLICT, e);
    }
}
