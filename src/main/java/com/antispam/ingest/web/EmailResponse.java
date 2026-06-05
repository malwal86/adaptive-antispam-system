package com.antispam.ingest.web;

import com.antispam.ingest.Email;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * GET /emails/{id} response: parsed metadata plus the byte-faithful raw content,
 * Base64-encoded so it survives JSON transport without modification. (The raw
 * bytes are also available verbatim at /emails/{id}/raw.)
 */
public record EmailResponse(
        UUID emailId,
        String contentHash,
        String source,
        Instant ingestedAt,
        String sender,
        String senderDomain,
        String recipients,
        String subject,
        Instant receivedAt,
        String authResults,
        String rawBase64) {

    public static EmailResponse from(Email email) {
        return new EmailResponse(
                email.id(),
                HexFormat.of().formatHex(email.contentHash()),
                email.ingestSource(),
                email.ingestedAt(),
                email.metadata().sender(),
                email.metadata().senderDomain(),
                email.metadata().recipients(),
                email.metadata().subject(),
                email.metadata().receivedAt(),
                email.metadata().authResults(),
                Base64.getEncoder().encodeToString(email.rawContent()));
    }
}
