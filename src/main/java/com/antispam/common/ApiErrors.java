package com.antispam.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Builds the shared error-response envelope used by every {@code @RestControllerAdvice}: a body of
 * {@code {"error": <message>}} at the given status. Centralizing the envelope keeps the shape
 * consistent across endpoints and changeable in one place; each handler still owns the mapping from
 * its domain exceptions to the appropriate status.
 */
public final class ApiErrors {

    private ApiErrors() {}

    /** A response with the given status and a {@code {"error": e.getMessage()}} body. */
    public static ResponseEntity<Map<String, String>> body(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
