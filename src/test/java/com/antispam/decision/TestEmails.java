package com.antispam.decision;

import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Test data builder for {@link Email} aggregates. Each factory overrides only the
 * fields a hard-rule test actually cares about (the raw body, or the parsed
 * sender domain + auth results) and fills the rest with harmless defaults, so a
 * test's data-relevance is obvious from which factory it calls.
 */
public final class TestEmails {

    private TestEmails() {
    }

    /** An email whose raw body is {@code body}; parsed metadata is empty. */
    public static Email bodyContaining(String body) {
        return build(body, null, null);
    }

    /**
     * An email with the given parsed sender domain and {@code Authentication-Results}
     * value; the raw body is irrelevant.
     */
    public static Email from(String senderDomain, String authResults) {
        return build("(body)", senderDomain, authResults);
    }

    private static Email build(String body, String senderDomain, String authResults) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        ParsedEmail metadata = new ParsedEmail(
                senderDomain == null ? null : "user@" + senderDomain,
                senderDomain, null, null, null, authResults);
        return new Email(UUID.randomUUID(), new byte[32], raw, metadata, "test", Instant.now());
    }
}
