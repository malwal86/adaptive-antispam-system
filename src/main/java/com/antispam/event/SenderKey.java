package com.antispam.event;

import java.util.Locale;

/**
 * Derives the Kafka partition key for an email from its sender identity.
 *
 * <p>Partitioning {@code emails.raw} by sender identity is what gives the system
 * per-sender serialization for free (PRD §Subsystem 3): every message from one
 * sender lands on the same partition, so a single consumer thread owns that
 * sender's reputation updates with no cross-partition locking. The price is
 * hot-sender partition skew, which the PRD accepts at demo scale.
 *
 * <p>The only property that must hold is <b>stability</b>: the same sender always
 * maps to the same key. Addresses are normalized (lowercased, trimmed) so that
 * cosmetic variants do not scatter one sender across partitions.
 */
public final class SenderKey {

    /**
     * Key for mail whose sender identity cannot be determined (no From address
     * and no domain). All such mail shares one partition; that bucket is a known
     * hot spot, accepted at demo scale rather than fanned out arbitrarily.
     */
    public static final String UNKNOWN = "unknown-sender";

    private SenderKey() {
    }

    /**
     * Maps a sender identity to a stable, non-blank partition key.
     *
     * @param sender       the From address (local@domain); may be null/blank
     * @param senderDomain the domain portion; used as a fallback when {@code sender}
     *                     is absent; may be null/blank
     * @return the normalized address, else the normalized domain, else {@link #UNKNOWN}
     */
    public static String of(String sender, String senderDomain) {
        String normalizedSender = normalize(sender);
        if (normalizedSender != null) {
            return normalizedSender;
        }
        String normalizedDomain = normalize(senderDomain);
        if (normalizedDomain != null) {
            return normalizedDomain;
        }
        return UNKNOWN;
    }

    /** Lowercase + trim, or null when the input is null/blank. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
