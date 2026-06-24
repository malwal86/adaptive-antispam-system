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
        ParsedEmail metadata = parser.parse(requireContent(rawContent));
        IngestResult result = persist(rawContent, metadata, source);
        // Publish onto the event spine only for a genuinely new email: the persist
        // has committed (this is the transactional-after-commit point), and a
        // duplicate's event was already emitted on first ingest, so skipping it
        // keeps "exactly one emails.raw message per stored email".
        if (!result.duplicate()) {
            publisher.publish(RawEmailEvent.of(result, metadata));
        }
        return result;
    }

    /**
     * Persists a canonical record exactly as {@link #ingest} does but <b>without</b> publishing it
     * onto the live event spine — no feature extraction, no reputation accrual, no live decision.
     * This is the entry point for experiment-scoped producers (the adversarial arena, story 08.01):
     * a generated variant must become an ordinary {@code emails} row so it can be scored through the
     * same pipeline as real mail, yet it must not perturb live state the way a real arrival would.
     * Scoring then happens read-only via {@link com.antispam.decision.policy.PolicyScorer}, keeping
     * the side-effect isolation the arena depends on (story 09.03).
     *
     * @param source ingest provenance; experiment producers pass their own (e.g. {@code adversarial})
     * @throws IllegalArgumentException if {@code rawContent} is null or empty
     */
    public IngestResult ingestOffSpine(byte[] rawContent, String source) {
        ParsedEmail metadata = parser.parse(requireContent(rawContent));
        return persist(rawContent, metadata, source);
    }

    private IngestResult persist(byte[] rawContent, ParsedEmail metadata, String source) {
        byte[] contentHash = sha256(rawContent);
        IngestResult result = repository.save(rawContent, contentHash, metadata, normalizeSource(source));
        // Audit log: the sender is masked and the body is never logged — only the
        // content hash (non-PII) identifies the message. See com.antispam.privacy.
        log.info("ingested email id={} source={} duplicate={} sender={} hash={}",
                result.emailId(), result.source(), result.duplicate(),
                Redaction.maskAddress(metadata.sender()), result.contentHashHex());
        return result;
    }

    private static byte[] requireContent(byte[] rawContent) {
        if (rawContent == null || rawContent.length == 0) {
            throw new IllegalArgumentException("raw email content must not be empty");
        }
        return rawContent;
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
