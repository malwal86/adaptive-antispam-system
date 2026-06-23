package com.antispam.experiment.replay.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a replay against an unknown policy version (surfaced as {@link IllegalArgumentException} by
 * {@code ReplayService}) to a 400 with the reason, rather than a 500.
 */
@RestControllerAdvice(assignableTypes = ReplayController.class)
public class ReplayExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
