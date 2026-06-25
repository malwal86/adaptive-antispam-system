package com.antispam.analyze.web;

import com.antispam.analyze.EmailNotFoundException;
import com.antispam.common.ApiErrors;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps analyzer input errors to clear HTTP responses: a malformed paste (empty
 * body, surfaced by ingest as {@link IllegalArgumentException}) becomes a 400, and
 * analysing an unknown email id becomes a 404.
 */
@RestControllerAdvice(assignableTypes = AnalyzeController.class)
public class AnalyzeExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ApiErrors.body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(EmailNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EmailNotFoundException e) {
        return ApiErrors.body(HttpStatus.NOT_FOUND, e);
    }
}
