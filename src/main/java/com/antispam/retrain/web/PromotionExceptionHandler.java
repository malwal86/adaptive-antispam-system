package com.antispam.retrain.web;

import com.antispam.decision.model.ModelArtifactNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the promotion endpoints' refusals to honest statuses rather than 500s (story 10.04), so the
 * scheduled retrain pipeline can tell a deliberate refusal from a bug:
 *
 * <ul>
 *   <li>{@link IllegalStateException} — the candidate did not pass the gate (or its model is
 *       unresolvable): a well-formed request to promote something that must not be promoted →
 *       {@code 409 Conflict}.
 *   <li>{@link ModelArtifactNotFoundException} — the candidate's artifact was never staged, so there is
 *       nothing to serve → {@code 404 Not Found}.
 *   <li>{@link IllegalArgumentException} — rollback to a policy version that does not exist →
 *       {@code 400 Bad Request}.
 * </ul>
 */
@RestControllerAdvice(assignableTypes = PromotionController.class)
public class PromotionExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNotPromotable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ModelArtifactNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleArtifactMissing(ModelArtifactNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleUnknownTarget(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
