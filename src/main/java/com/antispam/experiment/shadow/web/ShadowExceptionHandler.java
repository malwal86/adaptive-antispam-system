package com.antispam.experiment.shadow.web;

import com.antispam.common.ApiErrors;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps designating an unknown policy as shadow (surfaced as {@link IllegalArgumentException} by
 * {@code PolicyRepository.markShadow}) to a 400 with the reason, rather than a 500.
 */
@RestControllerAdvice(assignableTypes = ShadowController.class)
public class ShadowExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ApiErrors.body(HttpStatus.BAD_REQUEST, e);
    }
}
