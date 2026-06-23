package com.antispam.decision.llm;

import com.antispam.decision.routing.RoutingReason;
import com.antispam.features.EmailFeatures;
import com.antispam.features.FeatureSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The grounded context the LLM is reasoned over (story 05.03; PRD §Subsystem 5): the
 * structured signals the pipeline already extracted, the sender's reputation summary, and
 * <em>why the decision was escalated</em> — assembled into the trusted half of the prompt.
 * It exists so the model anchors its verdict to real, machine-derived evidence instead of
 * inventing authoritative-sounding nonsense from prose alone.
 *
 * <p><b>What it deliberately excludes.</b> No raw email body, no header text, and no system
 * instructions or secrets live here — only derived features (counts, ratios, auth tokens), a
 * numeric reputation summary, and the escalation reasons. The raw message is still handed to
 * the model, but separately and framed as untrusted <em>data</em> (the delimited block the
 * fallback service appends); the hardened injection defenses for that data are story 05.05.
 * Keeping the grounding free of raw content is what lets it be the trusted basis of the call.
 *
 * <p><b>Reproducibility (AC 4).</b> {@link #render()} is a pure function of the three fields,
 * each of which is itself deterministic for a given email and feature version. The same inputs
 * therefore always produce byte-identical context — so a recorded decision can be reconstructed
 * and audited, and two scorings of the same email + model never silently diverge.
 *
 * @param features         the versioned signals extracted at ingest (no raw body)
 * @param reputation       the sender's grounded reputation summary
 * @param escalationReasons why the router escalated this decision to the LLM (story 05.01)
 */
public record GroundedContext(
        EmailFeatures features,
        SenderReputationSummary reputation,
        List<RoutingReason> escalationReasons) {

    public GroundedContext {
        if (features == null) {
            throw new IllegalArgumentException("features are required");
        }
        if (reputation == null) {
            throw new IllegalArgumentException("reputation summary is required");
        }
        escalationReasons = List.copyOf(escalationReasons);
    }

    /**
     * Renders the grounded context as the plain-text block the prompt carries. Pure and
     * deterministic: no clock, no randomness, no I/O — see the reproducibility note above.
     */
    public String render() {
        FeatureSet f = features.features();
        FeatureSet.HeaderFeatures h = f.header();
        FeatureSet.LinkFeatures l = f.link();
        FeatureSet.TextFeatures t = f.text();
        FeatureSet.AuthFeatures a = f.auth();
        // Locale.ROOT so the decimal separator (and thus the rendered context) is stable across
        // hosts — the reproducibility the audit trail depends on.
        return String.format(Locale.ROOT, """
                === GROUNDED CONTEXT (trusted, machine-extracted — not the email's own words) ===
                Why escalated to you: %s
                Feature version: %d

                Header signals:
                  has_subject=%b subject_len=%d subject_uppercase_ratio=%.2f \
                subject_exclamations=%d has_sender=%b recipients=%d reply_to_differs_from_from=%b
                Link signals:
                  url_count=%d unique_domains=%d has_ip_url=%b has_punycode_domain=%b max_url_len=%d
                Text signals:
                  char_count=%d word_count=%d uppercase_ratio=%.2f exclamations=%d avg_word_len=%.2f
                Authentication: spf=%s dkim=%s dmarc=%s
                Sender reputation: trust_mean=%.3f evidence_count=%.1f uncertainty=%.3f \
                dmarc_aligned=%b
                === END GROUNDED CONTEXT ===\
                """,
                renderReasons(escalationReasons),
                features.featureVersion(),
                h.hasSubject(), h.subjectLength(), h.subjectUppercaseRatio(),
                h.subjectExclamationCount(), h.hasSender(), h.recipientCount(),
                h.replyToDiffersFromFrom(),
                l.urlCount(), l.uniqueDomainCount(), l.hasIpUrl(), l.hasPunycodeDomain(),
                l.maxUrlLength(),
                t.charCount(), t.wordCount(), t.uppercaseRatio(), t.exclamationCount(),
                t.avgWordLength(),
                token(a.spf()), token(a.dkim()), token(a.dmarc()),
                reputation.trustMean(), reputation.evidenceCount(), reputation.uncertainty(),
                reputation.dmarcAligned());
    }

    /** The escalation reasons as a stable, comma-separated list, or a sentinel when none fired. */
    private static String renderReasons(List<RoutingReason> reasons) {
        if (reasons.isEmpty()) {
            return "(unspecified)";
        }
        return reasons.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    /** An auth token, lower-cased for a stable rendering, or the extractor's sentinel when absent. */
    private static String token(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT);
    }
}
