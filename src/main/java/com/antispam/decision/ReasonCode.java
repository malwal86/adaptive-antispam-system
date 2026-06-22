package com.antispam.decision;

import java.util.List;
import java.util.stream.Stream;

/**
 * The fixed, closed vocabulary of machine-checkable reasons a decision can cite.
 * Reason codes are an enum on purpose: a downstream LLM (Epic 05) must choose
 * from this set rather than inventing authoritative-sounding free text, which
 * mitigates the "confident but wrong" failure mode.
 *
 * <p>This enum is the <b>single source of truth</b> for that vocabulary, shared by
 * three surfaces so they never drift (story 05.03 AC 3): the hard-rule engine
 * (Epic 01) emits these codes, the LLM contract ({@link com.antispam.decision.llm.LlmVerdict})
 * validates against them, and the analyzer UI renders them
 * ({@link com.antispam.analyze.AnalysisExplainer}). Adding a code forces a phrase in
 * the explainer to compile, so the UI can never lag the vocabulary.
 *
 * <p><b>Origin and who may assert a code.</b> Each code records its {@link Origin} —
 * the subsystem whose evidence establishes it. A {@link Origin#HARD_RULE} or
 * {@link Origin#DETECTOR} code is a <em>fact</em> a deterministic check established
 * (a denylist hit, a detected burst); the LLM has no way to verify such a fact and so
 * is never offered it. Only {@link Origin#LLM} codes — judgments about the message's
 * content the model is actually positioned to make — are {@link #availableToLlm()
 * exposed in the LLM prompt}. Validation still accepts any enum member (AC 2 rejects
 * only <em>off-enum</em> codes); the origin governs which codes the model is told it
 * may choose, so it cannot dress a content judgment up as a denylist fact.
 */
public enum ReasonCode {

    /** A URL in the message resolves to a denylisted host. */
    KNOWN_BAD_URL(Origin.HARD_RULE),

    /** Mail claims a high-value brand but its authentication (DMARC) is not aligned. */
    MALFORMED_AUTH_BRAND_SPOOF(Origin.HARD_RULE),

    /**
     * The mail is part of a detected burst/campaign, so its tier was escalated beyond
     * what the posterior alone would select (story 04.05 hook; the detector lands in
     * Epic 06). Records that the override, not the score, drove the verdict.
     */
    BURST_OVERRIDE(Origin.DETECTOR),

    /**
     * Links in the body exhibit phishing tells — a raw-IP host, a punycode
     * ({@code xn--}) domain, or a look-alike — short of an outright denylist hit
     * ({@link #KNOWN_BAD_URL} is the denylist fact; this is the model's judgment).
     */
    SUSPICIOUS_LINK(Origin.LLM),

    /** The message solicits credentials or impersonates an account-security / login flow. */
    CREDENTIAL_PHISHING(Origin.LLM),

    /** Manufactured urgency, threats, or pressure tactics push the reader to act fast. */
    URGENCY_PRESSURE(Origin.LLM),

    /** Prize, lottery, inheritance, or other financial-windfall bait. */
    PRIZE_OR_LOTTERY_BAIT(Origin.LLM),

    /** Generic unsolicited bulk or promotional content with no prior relationship. */
    UNSOLICITED_BULK(Origin.LLM),

    /** The sender's poor or highly uncertain reputation is a material factor in the verdict. */
    SENDER_REPUTATION_RISK(Origin.LLM),

    /** No abusive signal was found; supports a legitimate verdict with a positive reason. */
    BENIGN_CONTENT(Origin.LLM);

    /**
     * The kind of evidence that establishes a code — and thereby who may assert it. A
     * fact-establishing origin ({@link #HARD_RULE}, {@link #DETECTOR}) is not something
     * the LLM can verify, so only {@link #LLM} codes are offered to the model.
     */
    public enum Origin {
        /** A deterministic hard rule established the code (Epic 01). */
        HARD_RULE,
        /** A detector (e.g. burst/campaign) established the code. */
        DETECTOR,
        /** A content judgment the LLM is positioned to make (Epic 05). */
        LLM
    }

    private final Origin origin;

    ReasonCode(Origin origin) {
        this.origin = origin;
    }

    /** The subsystem whose evidence establishes this code. */
    public Origin origin() {
        return origin;
    }

    /**
     * Whether the LLM may assert this code — true only for {@link Origin#LLM} content
     * judgments. Hard-rule and detector facts are established before the LLM is ever
     * consulted and cannot be verified by it, so they are excluded from its contract.
     */
    public boolean availableToLlm() {
        return origin == Origin.LLM;
    }

    /** The codes the LLM is offered, in declaration order — the closed set its prompt lists. */
    public static List<ReasonCode> llmSelectable() {
        return Stream.of(values()).filter(ReasonCode::availableToLlm).toList();
    }
}
