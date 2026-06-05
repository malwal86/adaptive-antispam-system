package com.antispam.seed;

/**
 * Thrown when seeding cannot proceed: a missing or empty corpus directory, an
 * unreadable file, or an unrecognized class directory. It carries a message
 * naming the offending path so a failed seed run is diagnosable at a glance
 * (acceptance criterion: fail with a clear message, no ambiguous partial state).
 */
public class SeedException extends RuntimeException {

    public SeedException(String message) {
        super(message);
    }

    public SeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
