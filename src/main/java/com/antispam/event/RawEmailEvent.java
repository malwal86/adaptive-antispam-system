package com.antispam.event;

import com.antispam.ingest.IngestResult;
import com.antispam.ingest.ParsedEmail;
import java.util.UUID;

/**
 * The value envelope published to {@code emails.raw} once an email is durably
 * persisted. It carries identity and routing only — not the email body: the
 * canonical bytes live in Postgres (story 01.02) and downstream consumers load
 * them by {@code emailId}. Keeping the body out of the event avoids re-deriving
 * the redaction rules on the wire and keeps the topic small.
 *
 * <p><b>Schema evolution:</b> {@link #schemaVersion} is the first field so a
 * consumer can branch on it before trusting the rest. New fields must be added
 * (never repurposed) and old consumers must tolerate unknown ones — the JSON
 * deserializer is configured to ignore unknown properties for exactly this.
 *
 * @param schemaVersion envelope version; {@link #CURRENT_SCHEMA_VERSION} today
 * @param emailId       canonical id of the persisted email (the join key to Postgres)
 * @param senderKey     the partition key — normalized sender identity (see {@link SenderKey})
 * @param sender        raw From address as parsed; may be null
 * @param senderDomain  domain portion of the sender; may be null
 * @param contentHashHex hex-encoded SHA-256 of the raw bytes (idempotency key)
 * @param ingestSource  provenance of the ingest (api, seed, replay, ...)
 */
public record RawEmailEvent(
        int schemaVersion,
        UUID emailId,
        String senderKey,
        String sender,
        String senderDomain,
        String contentHashHex,
        String ingestSource) {

    /** Current envelope schema version. Bump when the shape changes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Builds the event for a freshly persisted email, computing the partition key
     * from the parsed sender identity.
     */
    public static RawEmailEvent of(IngestResult result, ParsedEmail metadata) {
        return new RawEmailEvent(
                CURRENT_SCHEMA_VERSION,
                result.emailId(),
                SenderKey.of(metadata.sender(), metadata.senderDomain()),
                metadata.sender(),
                metadata.senderDomain(),
                result.contentHashHex(),
                result.source());
    }
}
