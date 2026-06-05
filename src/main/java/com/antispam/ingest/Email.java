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
}
