package com.antispam.privacy;

import java.util.regex.Pattern;

/**
 * Masks personally-identifying email addresses for egress surfaces (logs, the
 * default API read view, the console). The canonical raw store is never
 * redacted — it is the source of truth and must stay byte-faithful for replay
 * and retraining; redaction happens only on the way out.
 *
 * <p>The domain is deliberately kept: it is the reputation/identity key the rest
 * of the system is built around, and it is far less identifying than the
 * local-part. Only the local-part is masked.
 */
public final class Redaction {

    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+\\-]+)@([A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})");

    private Redaction() {
    }

    /**
     * Masks a single email address ({@code alice@example.com -> a***@example.com}),
     * keeping the domain. Returns null for null/blank input, and {@code ***} for a
     * value that is not a usable address (so a failed parse never leaks).
     */
    public static String maskAddress(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return "***";
        }
        return maskLocalPart(trimmed.substring(0, at)) + "@" + trimmed.substring(at + 1);
    }

    /**
     * Masks the local-part of every email address found in free-form text (e.g. a
     * raw {@code To} header), leaving all other text and the domains intact.
     */
    public static String redactEmails(String text) {
        if (text == null) {
            return null;
        }
        // The replacement is applied literally (no group/$ expansion), so it is
        // safe against addresses that contain regex metacharacters.
        return EMAIL.matcher(text)
                .replaceAll(match -> maskLocalPart(match.group(1)) + "@" + match.group(2));
    }

    private static String maskLocalPart(String localPart) {
        // A one-character local-part would be fully revealed by "first char + ***",
        // so mask it entirely.
        if (localPart.length() <= 1) {
            return "***";
        }
        return localPart.charAt(0) + "***";
    }
}
