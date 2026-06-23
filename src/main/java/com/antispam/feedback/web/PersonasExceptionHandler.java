package com.antispam.feedback.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a bad population request (an out-of-range/empty spec or a weight naming an unknown persona,
 * both surfaced as {@link IllegalArgumentException}) to a 400 with the reason, rather than a 500.
 */
@RestControllerAdvice(assignableTypes = PersonasController.class)
public class PersonasExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
