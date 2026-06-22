package com.antispam.decision.llm;

import com.antispam.decision.ReasonCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The LLM fallback's strictly-typed verdict (story 05.02; PRD §Subsystem 5). When the router
 * escalates an uncertain decision (05.01), the model is asked to answer in exactly this shape —
 * {@code { verdict, spam_prob, phishing_prob, reason_codes[], explanation_short }} — and the
 * response is parsed into this record before it can touch the decision path. Making it a record
 * with a validating constructor is a <em>security control</em>, not a convenience: a malformed or
 * prompt-manipulated response fails to construct (out-of-range probability, missing field, an
 * invented reason code that is not in the {@link ReasonCode} enum) and is rejected, so the LLM
 * can never inject an authoritative-sounding but unvalidated decision.
 *
 * <p><b>Field names.</b> The JSON keys are snake_case to match the schema the model is instructed
 * to emit; the {@link JsonProperty} annotations are explicit so the contract does not depend on
 * Jackson's naming strategy or on {@code -parameters} being set. Each is {@code required = true}
 * so a <em>missing</em> field is rejected at bind time — value validation in the compact
 * constructor then rejects an out-of-range one, and {@code FAIL_ON_UNKNOWN_PROPERTIES} (set on the
 * parser) rejects an extra one. Missing, extra, and mistyped all fail, as the story requires.
 *
 * <p><b>Reason codes are a closed enum</b>, not free text — an unknown code fails enum binding and
 * is rejected (PRD §Subsystem 5). This story reuses the existing {@link ReasonCode} vocabulary;
 * the grounded prompt that tells the model which codes are valid, and the LLM-specific additions to
 * the enum, land in story 05.03.
 *
 * @param verdict          the model's categorical call
 * @param spamProb         the model's P(spam) in {@code [0,1]}
 * @param phishingProb     the model's P(phish) in {@code [0,1]}
 * @param reasonCodes      the machine-checkable reasons justifying the verdict, possibly empty;
 *                         every element must be a known {@link ReasonCode}
 * @param explanationShort a brief human-readable rationale, non-blank and at most
 *                         {@link #MAX_EXPLANATION_LENGTH} characters
 */
public record LlmVerdict(
        @JsonProperty(value = "verdict", required = true) Verdict verdict,
        @JsonProperty(value = "spam_prob", required = true) double spamProb,
        @JsonProperty(value = "phishing_prob", required = true) double phishingProb,
        @JsonProperty(value = "reason_codes", required = true) List<ReasonCode> reasonCodes,
        @JsonProperty(value = "explanation_short", required = true) String explanationShort) {

    /**
     * The categorical labels the LLM may return. Kept deliberately small and distinct from the
     * four decision {@link com.antispam.decision.Decision tiers}: this is the model's judgement of
     * <em>what the mail is</em>, which story 05.06 resolves into a tier under the hard-rule circuit
     * breaker — the LLM never picks the enforced action directly.
     */
    public enum Verdict {
        LEGITIMATE,
        SPAM,
        PHISHING
    }

    /** Upper bound on {@link #explanationShort}; a verbose response is itself a schema violation. */
    public static final int MAX_EXPLANATION_LENGTH = 600;

    public LlmVerdict {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required");
        }
        requireUnit("spam_prob", spamProb);
        requireUnit("phishing_prob", phishingProb);
        // List.copyOf rejects a null list (NPE) and any null element, and makes the field immutable.
        reasonCodes = List.copyOf(reasonCodes);
        if (explanationShort == null || explanationShort.isBlank()) {
            throw new IllegalArgumentException("explanation_short is required and must be non-blank");
        }
        if (explanationShort.length() > MAX_EXPLANATION_LENGTH) {
            throw new IllegalArgumentException(
                    "explanation_short must be at most " + MAX_EXPLANATION_LENGTH
                            + " characters, was: " + explanationShort.length());
        }
    }

    private static void requireUnit(String field, double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be in [0, 1], was: " + value);
        }
    }
}
