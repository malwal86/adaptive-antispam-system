package com.antispam.ingest;

import com.antispam.event.RawEmailEvent;
import com.antispam.event.RawEmailPublisher;
import com.antispam.privacy.Redaction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ingest orchestration: hash the raw bytes, parse their metadata, and persist
 * the canonical record idempotently. The content hash is what makes ingest
 * idempotent (re-submitting identical bytes returns the existing id).
 */
@Service
public class IngestService {

    private static final String DEFAULT_SOURCE = "api";

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EmailRepository repository;
    private final EmailParser parser;
    private final RawEmailPublisher publisher;

    @Autowired
    public IngestService(EmailRepository repository, EmailParser parser, RawEmailPublisher publisher) {
        this.repository = repository;
        this.parser = parser;
        this.publisher = publisher;
    }

    /**
     * Ingests raw email bytes.
     *
     * @param rawContent the byte-faithful raw message; must be non-empty
     * @param source     ingest provenance; defaults to {@code api} when null/blank
     * @throws IllegalArgumentException if {@code rawContent} is null or empty
     */
    public IngestResult ingest(byte[] rawContent, String source) {
        if (rawContent == null || rawContent.length == 0) {
            throw new IllegalArgumentException("raw email content must not be empty");
        }
        byte[] contentHash = sha256(rawContent);
        ParsedEmail metadata = parser.parse(rawContent);
        IngestResult result = repository.save(rawContent, contentHash, metadata, normalizeSource(source));
        // Audit log: the sender is masked and the body is never logged — only the
        // content hash (non-PII) identifies the message. See com.antispam.privacy.
        log.info("ingested email id={} source={} duplicate={} sender={} hash={}",
                result.emailId(), result.source(), result.duplicate(),
                Redaction.maskAddress(metadata.sender()), result.contentHashHex());
        // Publish onto the event spine only for a genuinely new email: the persist
        // has committed (this is the transactional-after-commit point), and a
        // duplicate's event was already emitted on first ingest, so skipping it
        // keeps "exactly one emails.raw message per stored email".
        if (!result.duplicate()) {
            publisher.publish(RawEmailEvent.of(result, metadata));
        }
        return result;
    }

    public Optional<Email> findById(UUID id) {
        return repository.findById(id);
    }

    private static String normalizeSource(String source) {
        return (source == null || source.isBlank()) ? DEFAULT_SOURCE : source.trim();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS to be present on every JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
