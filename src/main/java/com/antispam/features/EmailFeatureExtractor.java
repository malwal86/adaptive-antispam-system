package com.antispam.features;

import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import com.antispam.ingest.Email;
import com.antispam.ingest.MimeMessages;
import com.antispam.ingest.ParsedEmail;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a stored {@link Email} into a versioned {@link FeatureSet}. This is the
 * one deep module the consumer calls; the per-concern logic lives in the public
 * {@code static} methods below so each can be unit-tested with plain inputs
 * without standing up Spring or a database.
 *
 * <p><b>Total and deterministic.</b> Every method here is best-effort: a missing
 * or malformed input degrades to a sentinel rather than throwing, so a hostile or
 * truncated email can never crash the consumer or poison a partition (story 02.02
 * AC 4). No method consults the wall clock or any random source, so the same email
 * at the same {@link #FEATURE_VERSION} always yields equal features (AC 5).
 */
@Component
public class EmailFeatureExtractor {

    /**
     * The extractor contract version stamped onto every row. Bump this whenever the
     * meaning, set, or computation of any feature changes, so a re-extraction lands
     * in a new {@code (email_id, feature_version)} row instead of silently changing
     * what an already-trained model thought it consumed.
     */
    public static final int FEATURE_VERSION = 1;

    /** Token returned for an auth method the header did not assert. */
    private static final String AUTH_UNKNOWN = "unknown";

    /** {@code method=result} matchers for the three auth methods, precompiled (one per email, ×3). */
    private static final Pattern SPF_RESULT = authPattern("spf");
    private static final Pattern DKIM_RESULT = authPattern("dkim");
    private static final Pattern DMARC_RESULT = authPattern("dmarc");

    /** http/https URLs, stopping at whitespace, quotes, angle brackets, or a closing paren. */
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\"'<>)]+");

    /** A bare email address, used to compare Reply-To against From. */
    private static final Pattern EMAIL = Pattern.compile("[^\\s<>@]+@[^\\s<>@]+");

    /** Dotted-quad IPv4 host (a common phishing-link tell). */
    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    /** Strips HTML tags so the readable text remains for text statistics. */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    /**
     * Extracts the full versioned feature record for a stored email. Header,
     * timing, and auth signals come from the metadata parsed at ingest; link and
     * text signals are derived from the decoded body.
     *
     * @throws IllegalArgumentException if {@code email} is null
     */
    public EmailFeatures extract(Email email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }
        ParsedEmail md = email.metadata();
        Body body = body(email.rawContent());

        FeatureSet features = new FeatureSet(
                headerFeatures(md.subject(), md.sender(), md.recipients(), body.replyTo()),
                linkFeatures(body.raw()),
                textFeatures(body.display()),
                timingFeatures(md.receivedAt()),
                authFeatures(md.authResults()),
                // Embedding hook: deliberately empty here; populated by Epic 04.03.
                null);

        // extractedAt is left null: the database stamps it on write and it is read
        // back on retrieval. It is metadata, not a feature, so it is excluded from
        // the determinism contract.
        return new EmailFeatures(email.id(), FEATURE_VERSION, features, null);
    }

    // ---- Header anomalies ---------------------------------------------------

    /**
     * Computes header anomaly features. All arguments are nullable; a null subject,
     * sender, recipients, or reply-to degrades to the corresponding sentinel.
     */
    public static HeaderFeatures headerFeatures(String subject, String sender,
            String recipients, String replyTo) {
        boolean hasSubject = isPresent(subject);
        return new HeaderFeatures(
                hasSubject,
                subject == null ? 0 : subject.length(),
                uppercaseRatio(subject),
                count(subject, '!'),
                isPresent(sender),
                recipientCount(recipients),
                replyToDiffers(sender, replyTo));
    }

    private static int recipientCount(String recipients) {
        if (!isPresent(recipients)) {
            return 0;
        }
        int count = 0;
        for (String part : recipients.split(",")) {
            if (isPresent(part)) {
                count++;
            }
        }
        return count;
    }

    private static boolean replyToDiffers(String sender, String replyTo) {
        String from = firstAddress(sender);
        String reply = firstAddress(replyTo);
        return from != null && reply != null && !from.equals(reply);
    }

    private static String firstAddress(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = EMAIL.matcher(value);
        return m.find() ? m.group().toLowerCase() : null;
    }

    // ---- Links --------------------------------------------------------------

    /** Computes URL/link features from a body; a null/empty body yields all zeros. */
    public static LinkFeatures linkFeatures(String body) {
        if (!isPresent(body)) {
            return new LinkFeatures(0, 0, false, false, 0);
        }
        int urlCount = 0;
        int maxLength = 0;
        boolean hasIp = false;
        boolean hasPunycode = false;
        Set<String> domains = new HashSet<>();

        Matcher m = URL.matcher(body);
        while (m.find()) {
            String url = m.group();
            urlCount++;
            maxLength = Math.max(maxLength, url.length());
            String host = host(url);
            if (host != null) {
                domains.add(host);
                if (IPV4.matcher(host).matches()) {
                    hasIp = true;
                }
                if (host.contains("xn--")) {
                    hasPunycode = true;
                }
            }
        }
        return new LinkFeatures(urlCount, domains.size(), hasIp, hasPunycode, maxLength);
    }

    /** Extracts the lowercased hostname from a URL, dropping userinfo, port, and path. */
    private static String host(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String rest = url.substring(scheme + 3);
        int end = indexOfAny(rest, '/', '?', '#');
        String authority = end < 0 ? rest : rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.isEmpty() ? null : authority.toLowerCase();
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    // ---- Text statistics ----------------------------------------------------

    /** Computes text statistics from a body; a null/empty body yields all zeros. */
    public static TextFeatures textFeatures(String body) {
        String text = body == null ? "" : body;
        String trimmed = text.strip();
        String[] words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");

        int wordCharTotal = 0;
        for (String w : words) {
            wordCharTotal += w.length();
        }
        double avgWordLength = words.length == 0 ? 0.0 : round6((double) wordCharTotal / words.length);

        return new TextFeatures(
                text.length(),
                words.length,
                uppercaseRatio(text),
                count(text, '!'),
                avgWordLength);
    }

    // ---- Timing -------------------------------------------------------------

    /** Computes timing features from the email's Date header (UTC); null → sentinels. */
    public static TimingFeatures timingFeatures(Instant receivedAt) {
        if (receivedAt == null) {
            return new TimingFeatures(false, null, null, false);
        }
        ZonedDateTime utc = ZonedDateTime.ofInstant(receivedAt, ZoneOffset.UTC);
        int dayOfWeek = utc.getDayOfWeek().getValue();
        boolean weekend = dayOfWeek >= 6;
        return new TimingFeatures(true, utc.getHour(), dayOfWeek, weekend);
    }

    // ---- Auth ---------------------------------------------------------------

    /**
     * Parses SPF/DKIM/DMARC results from an {@code Authentication-Results} header.
     * A null header, or a method the header omits, yields {@code "unknown"}.
     */
    public static AuthFeatures authFeatures(String authResultsHeader) {
        return new AuthFeatures(
                authResult(authResultsHeader, SPF_RESULT),
                authResult(authResultsHeader, DKIM_RESULT),
                authResult(authResultsHeader, DMARC_RESULT));
    }

    /** Compiles the case-insensitive {@code <method>=<result>} matcher for one auth method. */
    private static Pattern authPattern(String method) {
        return Pattern.compile("(?i)\\b" + method + "\\s*=\\s*([a-z]+)");
    }

    private static String authResult(String header, Pattern methodResult) {
        if (!isPresent(header)) {
            return AUTH_UNKNOWN;
        }
        Matcher m = methodResult.matcher(header);
        return m.find() ? m.group(1).toLowerCase() : AUTH_UNKNOWN;
    }

    // ---- Body decoding ------------------------------------------------------

    /**
     * The decoded body in two views: {@code raw} keeps HTML markup so link
     * extraction can see {@code href} URLs; {@code display} strips markup for text
     * statistics. {@code replyTo} is the Reply-To header, or null.
     */
    private record Body(String raw, String display, String replyTo) {
    }

    private static Body body(byte[] rawContent) {
        MimeMessage message = MimeMessages.parse(rawContent);
        if (message == null) {
            return new Body("", "", null);
        }
        StringBuilder raw = new StringBuilder();
        StringBuilder display = new StringBuilder();
        collectText(message, raw, display);
        return new Body(raw.toString(), display.toString().strip(), MimeMessages.header(message, "Reply-To"));
    }

    /** Recursively appends text parts: plain text to both views, HTML to raw and stripped to display. */
    private static void collectText(Part part, StringBuilder raw, StringBuilder display) {
        try {
            Object content = part.getContent();
            if (part.isMimeType("text/plain") && content instanceof String text) {
                raw.append(text).append('\n');
                display.append(text).append('\n');
            } else if (part.isMimeType("text/html") && content instanceof String html) {
                raw.append(html).append('\n');
                display.append(stripHtml(html)).append('\n');
            } else if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collectText(multipart.getBodyPart(i), raw, display);
                }
            }
        } catch (Exception e) {
            // Best-effort: an unreadable part contributes nothing rather than failing.
        }
    }

    private static String stripHtml(String html) {
        return HTML_TAG.matcher(html).replaceAll(" ");
    }

    // ---- Shared helpers -----------------------------------------------------

    /** Uppercase letters ÷ all letters, rounded; 0.0 when the text has no letters. */
    private static double uppercaseRatio(String text) {
        if (text == null) {
            return 0.0;
        }
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters == 0 ? 0.0 : round6((double) upper / letters);
    }

    private static int count(String text, char target) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                n++;
            }
        }
        return n;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Rounds to 6 decimal places so serialized ratios are stable across runs. */
    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
