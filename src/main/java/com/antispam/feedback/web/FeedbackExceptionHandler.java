package com.antispam.feedback.web;

import com.antispam.common.ApiErrors;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a bad simulation request (an out-of-range spec or a weight naming an unknown persona, both
 * surfaced as {@link IllegalArgumentException}) to a 400 with the reason, rather than a 500.
 */
@RestControllerAdvice(assignableTypes = FeedbackController.class)
public class FeedbackExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ApiErrors.body(HttpStatus.BAD_REQUEST, e);
    }
}
