package com.antispam.ingest;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Extracts header metadata from raw RFC-822 email bytes using Jakarta Mail.
 *
 * <p>Parsing is best-effort and total: each field is read independently and any
 * extraction failure (missing header, malformed address, bad date) yields null
 * for that field instead of propagating. This keeps ingest robust against the
 * messy real-world mail that the system exists to defend against — the raw bytes
 * stored alongside this metadata are always the source of truth.
 */
@Component
public class EmailParser {

    private static final Session SESSION = Session.getInstance(new Properties());

    public ParsedEmail parse(byte[] raw) {
        MimeMessage message = toMimeMessage(raw);
        if (message == null) {
            return new ParsedEmail(null, null, null, null, null, null);
        }
        String sender = firstFromAddress(message);
        return new ParsedEmail(
                sender,
                domainOf(sender),
                header(message, "To"),
                subject(message),
                sentInstant(message),
                header(message, "Authentication-Results"));
    }

    private static MimeMessage toMimeMessage(byte[] raw) {
        try {
            return new MimeMessage(SESSION, new ByteArrayInputStream(raw));
        } catch (Exception e) {
            // Unparseable as a MIME message at all — metadata is simply unknown.
            return null;
        }
    }

    private static String firstFromAddress(MimeMessage message) {
        try {
            var from = message.getFrom();
            if (from == null || from.length == 0) {
                return null;
            }
            // InternetAddress.toUnicodeString()/getAddress() isn't exposed on the
            // base Address type; the textual form is the address for our purposes.
            if (from[0] instanceof jakarta.mail.internet.InternetAddress internet) {
                return blankToNull(internet.getAddress());
            }
            return blankToNull(from[0].toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String domainOf(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        return address.substring(at + 1);
    }

    private static String subject(MimeMessage message) {
        try {
            return blankToNull(message.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant sentInstant(MimeMessage message) {
        try {
            Date date = message.getSentDate();
            return date == null ? null : date.toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the named header(s) joined, or null if absent. */
    private static String header(MimeMessage message, String name) {
        try {
            return blankToNull(message.getHeader(name, ", "));
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
