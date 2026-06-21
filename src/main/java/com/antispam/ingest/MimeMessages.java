package com.antispam.ingest;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.util.Properties;

/**
 * Shared, best-effort Jakarta Mail parsing used by both the ingest-time
 * {@link EmailParser} (header metadata) and the feature extractor (body re-parse).
 * Both need to turn raw RFC-822 bytes into a {@link MimeMessage} and read headers,
 * and both follow the same contract: never throw, degrade to {@code null} for
 * anything missing or malformed, and treat the stored raw bytes as the source of
 * truth. Keeping that contract in one place avoids two copies drifting apart.
 */
public final class MimeMessages {

    // A no-op session is all MimeMessage parsing needs; it is immutable and thread-safe.
    private static final Session SESSION = Session.getInstance(new Properties());

    private MimeMessages() {
    }

    /**
     * Parses raw message bytes into a {@link MimeMessage}, or returns {@code null}
     * if the bytes are absent/empty or cannot be parsed as MIME at all.
     */
    public static MimeMessage parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            return new MimeMessage(SESSION, new ByteArrayInputStream(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the named header(s) joined by {@code ", "}, or {@code null} if absent or blank. */
    public static String header(MimeMessage message, String name) {
        try {
            String value = message.getHeader(name, ", ");
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }
}
