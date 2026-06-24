package com.antispam.arena.web;

import com.antispam.arena.AttackerUnavailableException;
import com.antispam.arena.MutationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the arena's failure modes to honest HTTP status across both the mutation endpoints (story
 * 08.01) and the bounded-loop endpoints (story 08.02): a bad seed, an invalid run config, or a
 * degenerate perturbation ({@link MutationException}) is the caller's problem → 400; the attacker
 * model being unreachable ({@link AttackerUnavailableException}) is a dependency outage, not a bad
 * request → 503.
 */
@RestControllerAdvice(assignableTypes = {MutationController.class, AttackRunController.class})
public class ArenaExceptionHandler {

    @ExceptionHandler(MutationException.class)
    public ResponseEntity<Map<String, String>> handleBadSeed(MutationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AttackerUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleAttackerDown(AttackerUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}
