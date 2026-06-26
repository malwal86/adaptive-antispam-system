package com.antispam.privacy;

import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Scrubs direct identifiers out of free-form email content before it is sent to the
 * external LLM (story 14.03). The LLM call is the single largest PII-egress event in
 * the pipeline — message text leaves our boundary to a third-party model — so the
 * identifiers are masked first, while the linguistic signal the model actually needs
 * to judge spam/phishing is preserved.
 *
 * <p><b>What is masked:</b> email addresses (local-part hidden, domain kept — the
 * domain carries the phishing signal), phone numbers, and obvious card/account
 * numbers. <b>What is preserved:</b> URLs (verbatim, even when they contain long
 * digit runs), and all ordinary words — so brand mentions and urgency language, the
 * very features a classifier keys on, survive untouched.
 *
 * <p><b>Single pass, URL-first.</b> Masking is one regex pass over an alternation of
 * {@code URL | email | card | phone}. Because the URL alternative is tried first and
 * consumes the whole URL span, digit runs <em>inside</em> a URL are never mistaken
 * for a card or phone number. The masker is pure, null-safe, and idempotent: its
 * outputs ({@code a***@domain}, {@code [phone]}, {@code [card-number]}) contain no
 * pattern it would mask again.
 */
public final class PiiMasker {

    private static final String PHONE_TOKEN = "[phone]";
    private static final String CARD_TOKEN = "[card-number]";

    /** A phone is only masked when its digit count is in this human-plausible range. */
    private static final int MIN_PHONE_DIGITS = 7;
    private static final int MAX_PHONE_DIGITS = 15;

    /**
     * One alternation, tried left-to-right per match position. URL first so a URL's
     * insides are protected; card before phone so longer grouped numbers are tagged as
     * cards rather than greedily eaten as phones.
     */
    private static final Pattern PII = Pattern.compile(
            "(?<url>https?://\\S+|www\\.\\S+)"
            + "|(?<email>[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})"
            + "|(?<card>\\b\\d{4}(?:[ -]?\\d{4}){2,4}\\b)"
            + "|(?<phone>\\+?\\(?\\d[\\d ().\\-]{5,}\\d)");

    private PiiMasker() {
    }

    /**
     * Returns {@code text} with direct identifiers masked. Null/blank input is returned
     * unchanged (null stays null).
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return PII.matcher(text).replaceAll(PiiMasker::replace);
    }

    private static String replace(MatchResult match) {
        if (match.group("url") != null) {
            // Preserve URLs verbatim — they're a primary phishing signal, not PII to hide.
            return match.group("url");
        }
        if (match.group("email") != null) {
            return Redaction.maskAddress(match.group("email"));
        }
        if (match.group("card") != null) {
            return CARD_TOKEN;
        }
        String phone = match.group("phone");
        // The phone pattern is loose (it has to span varied separators), so confirm the
        // digit count is phone-like; otherwise leave the text as-is rather than over-mask.
        long digits = phone.chars().filter(Character::isDigit).count();
        return (digits >= MIN_PHONE_DIGITS && digits <= MAX_PHONE_DIGITS) ? PHONE_TOKEN : phone;
    }
}
