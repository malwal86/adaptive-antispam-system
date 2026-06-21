package com.antispam.features;

import java.util.List;

/**
 * The versioned bundle of signals extracted from one email — the serialized
 * payload of an {@link EmailFeatures} row. It is grouped by concern (header,
 * link, text, timing, auth) so a reader can find a signal by what it describes
 * rather than by where in the message it came from, and so each group can be
 * unit-tested in isolation.
 *
 * <p><b>Determinism.</b> Every field here is a pure function of the email bytes
 * and the {@code feature_version}; nothing depends on wall-clock time, random
 * state, or extraction order. Re-extracting the same email at the same version
 * yields an equal {@code FeatureSet} — the property the model and offline
 * retrains rely on (story 02.02 AC 5).
 *
 * <p><b>Missing data.</b> A malformed or partial email never fails extraction;
 * absent signals degrade to sentinels (0 / 0.0 / {@code false} / {@code "unknown"})
 * rather than nulls inside the groups, so downstream consumers read a complete,
 * fixed-shape vector regardless of input quality.
 *
 * @param header    header-level anomaly signals
 * @param link      URL / link analysis of the body
 * @param text      text statistics of the decoded body
 * @param timing    signals derived from the email's own timestamps
 * @param auth      SPF / DKIM / DMARC results (PRD §Subsystem 3: auth is a feature)
 * @param embedding dense semantic vector, or {@code null} until Epic 04.03 fills
 *                  it — the deliberate <i>embedding hook</i> this story leaves open
 */
public record FeatureSet(
        HeaderFeatures header,
        LinkFeatures link,
        TextFeatures text,
        TimingFeatures timing,
        AuthFeatures auth,
        List<Float> embedding) {

    /**
     * Header anomaly signals. Subject "shouting" (uppercase ratio, exclamation
     * runs) and a Reply-To that differs from From are classic spam/phish tells.
     *
     * @param hasSubject              whether a non-blank Subject is present
     * @param subjectLength           Subject character count (0 when absent)
     * @param subjectUppercaseRatio   uppercase letters ÷ letters in the Subject, 0.0 when none
     * @param subjectExclamationCount number of {@code '!'} in the Subject
     * @param hasSender               whether a From address was parsed
     * @param recipientCount          number of addresses on the To header (0 when absent)
     * @param replyToDiffersFromFrom  whether a Reply-To address is present and differs from From
     */
    public record HeaderFeatures(
            boolean hasSubject,
            int subjectLength,
            double subjectUppercaseRatio,
            int subjectExclamationCount,
            boolean hasSender,
            int recipientCount,
            boolean replyToDiffersFromFrom) {
    }

    /**
     * URL / link analysis of the decoded body. Raw-IP hosts and punycode
     * ({@code xn--}) domains are common in phishing links.
     *
     * @param urlCount          number of http/https URLs found in the body
     * @param uniqueDomainCount distinct hostnames across those URLs
     * @param hasIpUrl          whether any URL's host is a bare IPv4 address
     * @param hasPunycodeDomain whether any URL's host contains an {@code xn--} label
     * @param maxUrlLength      length of the longest URL (0 when none)
     */
    public record LinkFeatures(
            int urlCount,
            int uniqueDomainCount,
            boolean hasIpUrl,
            boolean hasPunycodeDomain,
            int maxUrlLength) {
    }

    /**
     * Text statistics of the decoded body.
     *
     * @param charCount        character count of the decoded, stripped body
     * @param wordCount        whitespace-delimited token count
     * @param uppercaseRatio   uppercase letters ÷ letters, 0.0 when there are no letters
     * @param exclamationCount number of {@code '!'} in the body
     * @param avgWordLength    mean token length, 0.0 when there are no words
     */
    public record TextFeatures(
            int charCount,
            int wordCount,
            double uppercaseRatio,
            int exclamationCount,
            double avgWordLength) {
    }

    /**
     * Signals derived from the email's own Date header (never the wall clock, so
     * the feature stays deterministic). Hour and day are evaluated in UTC.
     *
     * @param hasDate      whether a parseable Date was present
     * @param hourOfDayUtc hour 0–23 in UTC, or {@code null} when no date
     * @param dayOfWeek    ISO day 1 (Mon)–7 (Sun), or {@code null} when no date
     * @param weekend      whether the day is Saturday or Sunday ({@code false} when no date)
     */
    public record TimingFeatures(
            boolean hasDate,
            Integer hourOfDayUtc,
            Integer dayOfWeek,
            boolean weekend) {
    }

    /**
     * Email-authentication outcomes parsed from the {@code Authentication-Results}
     * header. Each is a normalized lowercase token (e.g. {@code pass}, {@code fail},
     * {@code softfail}, {@code none}); {@code "unknown"} means the header did not
     * assert that method. Per PRD §Subsystem 3 these are model features, not gates —
     * a misconfigured-but-legitimate sender can still earn trust via content.
     *
     * @param spf   SPF result token, or {@code "unknown"}
     * @param dkim  DKIM result token, or {@code "unknown"}
     * @param dmarc DMARC result token, or {@code "unknown"}
     */
    public record AuthFeatures(
            String spf,
            String dkim,
            String dmarc) {
    }
}
