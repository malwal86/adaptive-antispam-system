package com.antispam.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored canonical email: the byte-faithful raw record plus the metadata
 * parsed from it at ingest. Retrieved via {@link EmailRepository#findById}.
 *
 * @param id           canonical identifier
 * @param contentHash  SHA-256 of {@code rawContent} (the idempotency key)
 * @param rawContent   the original message bytes, exactly as received
 * @param metadata     header metadata parsed at ingest
 * @param ingestSource provenance of the ingest (api, seed, replay, ...)
 * @param ingestedAt   when the record was first stored
 */
public record Email(
        UUID id,
        byte[] contentHash,
        byte[] rawContent,
        ParsedEmail metadata,
        String ingestSource,
        Instant ingestedAt) {

    /**
     * Whether this email's body has been crypto-shredded (story 14.02): its data key
     * was destroyed to honor an erasure request, so the immutable ciphertext is no
     * longer recoverable and the body reads back as empty. A genuinely stored body is
     * never empty (ingest rejects empty content), so empty bytes unambiguously mean
     * "content erased" — the egress views render that state instead of a body.
     */
    public boolean contentErased() {
        return rawContent.length == 0;
    }
}
