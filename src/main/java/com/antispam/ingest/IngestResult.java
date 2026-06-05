package com.antispam.ingest;

import java.util.UUID;

/**
 * Outcome of an ingest attempt.
 *
 * @param emailId         the canonical id (newly created, or the pre-existing one on a duplicate)
 * @param contentHashHex  hex-encoded SHA-256 of the raw content
 * @param duplicate       true when identical bytes were already stored (no new row was created)
 * @param source          the recorded ingest source
 */
public record IngestResult(UUID emailId, String contentHashHex, boolean duplicate, String source) {
}
