package com.antispam.ingest.web;

import com.antispam.ingest.Email;
import com.antispam.privacy.Redaction;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * GET /emails/{id} response.
 *
 * <p><b>Redacted by default:</b> the sender and recipients have their local-parts
 * masked and the raw content is omitted, so casual reads do not expose PII. The
 * full, byte-faithful view is opt-in via {@code from(email, reveal=true)} (the
 * controller's {@code ?reveal=true}) and must be access-controlled once authz
 * exists. The domain is always kept — it is the reputation key, not sensitive on
 * its own.
 *
 * <p>Null fields are omitted from the JSON ({@code @JsonInclude(NON_NULL)}), so a
 * redacted response simply has no {@code rawBase64} key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    /**
     * Builds the response.
     *
     * @param reveal when false (the default), mask sender/recipients and omit the
     *               raw content; when true, return the full unredacted record
     */
    public static EmailResponse from(Email email, boolean reveal) {
        var metadata = email.metadata();
        return new EmailResponse(
                email.id(),
                HexFormat.of().formatHex(email.contentHash()),
                email.ingestSource(),
                email.ingestedAt(),
                reveal ? metadata.sender() : Redaction.maskAddress(metadata.sender()),
                metadata.senderDomain(),
                reveal ? metadata.recipients() : Redaction.redactEmails(metadata.recipients()),
                metadata.subject(),
                metadata.receivedAt(),
                metadata.authResults(),
                reveal ? Base64.getEncoder().encodeToString(email.rawContent()) : null);
    }
}
