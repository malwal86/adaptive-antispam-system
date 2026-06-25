package com.antispam.controls.web;

import com.antispam.common.ApiErrors;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the control endpoints' refusals to honest statuses rather than 500s: an invalid threshold
 * ladder, an unknown policy version, or an out-of-range budget cap ({@link IllegalArgumentException})
 * is a bad request → 400; deriving thresholds with no active policy to start from
 * ({@link IllegalStateException}) is a state conflict → 409.
 */
@RestControllerAdvice(assignableTypes = ControlsController.class)
public class ControlsExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ApiErrors.body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNoActivePolicy(IllegalStateException e) {
        return ApiErrors.body(HttpStatus.CONFLICT, e);
    }
}
