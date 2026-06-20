package com.antispam.seed;

import java.util.UUID;

/**
 * A labeled seed-corpus email surfaced for the analyzer's sample picker: enough to
 * show in a list (its ground-truth class, provenance, and subject) and to analyse
 * by id without re-pasting. Carries no address PII — only the subject and the
 * sender domain (the reputation key, not sensitive on its own).
 *
 * @param emailId       the canonical email to analyse by id
 * @param label         its ground-truth class (ham / spam / phish)
 * @param datasetSource the public corpus it came from
 * @param subject       the parsed subject, or null
 * @param senderDomain  the sender's domain, or null
 */
public record SeedSample(
        UUID emailId,
        GroundTruthLabel label,
        String datasetSource,
        String subject,
        String senderDomain) {
}
