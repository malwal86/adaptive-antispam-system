package com.antispam.analyze;

import java.util.UUID;

/**
 * Thrown when an analyse-by-id request names an email that does not exist. The
 * controller's advice maps it to a 404 — distinct from the 400 a malformed paste
 * (empty body) produces.
 */
public class EmailNotFoundException extends RuntimeException {

    public EmailNotFoundException(UUID emailId) {
        super("no email with id " + emailId);
    }
}
