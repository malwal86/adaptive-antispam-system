package com.antispam.ingest.web;

import com.antispam.ingest.IngestResult;
import java.util.UUID;

/** POST /emails response: the canonical id and whether the bytes were a duplicate. */
public record IngestResponse(UUID emailId, String contentHash, boolean duplicate, String source) {

    public static IngestResponse from(IngestResult result) {
        return new IngestResponse(
                result.emailId(), result.contentHashHex(), result.duplicate(), result.source());
    }
}
