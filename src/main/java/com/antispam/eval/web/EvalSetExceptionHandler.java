package com.antispam.eval.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the eval-set service's domain errors into honest statuses rather than 500s. Re-freezing an
 * existing golden version, or freezing under a blank label, is a well-formed request the immutability
 * rule refuses ({@link IllegalArgumentException} from {@code EvalSetService.freezeGolden}) → {@code 409
 * Conflict}: the caller asked to redefine something that is frozen, and should choose a new label
 * rather than treat it as a server fault.
 */
@RestControllerAdvice(assignableTypes = EvalSetController.class)
public class EvalSetExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleImmutable(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
