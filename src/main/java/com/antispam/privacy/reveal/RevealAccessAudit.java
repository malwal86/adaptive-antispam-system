package com.antispam.privacy.reveal;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded privileged access to email PII (story 14.05): who unmasked which email,
 * in what way, and when.
 *
 * @param id         audit row identifier
 * @param emailId    the email whose unredacted view was accessed
 * @param actor      the authorized principal that accessed it
 * @param accessType reveal / raw / erasure
 * @param at         when the access happened
 */
public record RevealAccessAudit(
        UUID id, UUID emailId, String actor, String accessType, Instant at) {
}
