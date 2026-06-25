package com.antispam.experiment.replay.web;

import com.antispam.common.ApiErrors;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the A/B harness's two failure modes to honest statuses rather than a 500:
 *
 * <ul>
 *   <li>An A/B against an unknown policy version ({@link IllegalArgumentException} from
 *       {@code ReplayAbService.startAb}) → {@code 400 Bad Request}.</li>
 *   <li>A comparison of a run whose decisions have not landed yet, or a corpus with no ground-truth
 *       labels ({@link IllegalStateException} from {@code compare}) → {@code 409 Conflict}: the
 *       request is well-formed but the run is not yet comparable, so the caller should poll.</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = ReplayAbController.class)
public class ReplayAbExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ApiErrors.body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNotYetComparable(IllegalStateException e) {
        return ApiErrors.body(HttpStatus.CONFLICT, e);
    }
}
