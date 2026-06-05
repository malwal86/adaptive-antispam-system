package com.antispam.ingest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
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

    private final EmailRepository repository;
    private final EmailParser parser;

    @Autowired
    public IngestService(EmailRepository repository, EmailParser parser) {
        this.repository = repository;
        this.parser = parser;
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
        return repository.save(rawContent, contentHash, metadata, normalizeSource(source));
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
