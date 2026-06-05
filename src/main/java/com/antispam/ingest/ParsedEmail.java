package com.antispam.ingest;

import java.time.Instant;

/**
 * Metadata extracted from a raw email's headers. Every field is nullable: the
 * raw bytes are authoritative, and headers are frequently absent or malformed,
 * so a missing field is represented as {@code null} rather than an error.
 *
 * @param sender        the From address (local@domain), or null
 * @param senderDomain  the domain portion of {@code sender}, or null
 * @param recipients    the raw To header value(s), or null
 * @param subject       the decoded Subject, or null
 * @param receivedAt    the Date header as an instant, or null
 * @param authResults   the Authentication-Results header(s), or null
 */
public record ParsedEmail(
        String sender,
        String senderDomain,
        String recipients,
        String subject,
        Instant receivedAt,
        String authResults) {
}
