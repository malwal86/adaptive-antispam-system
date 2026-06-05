package com.antispam.decision;

/**
 * The fixed, closed vocabulary of machine-checkable reasons a decision can cite.
 * Reason codes are an enum on purpose: a downstream LLM (Epic 05) must choose
 * from this set rather than inventing authoritative-sounding free text, which
 * mitigates the "confident but wrong" failure mode.
 *
 * <p>This story (01.04) defines the hard-rule codes. Later stories extend the
 * enum as new signals appear (the reason-code surface is built out in 05.03).
 */
public enum ReasonCode {

    /** A URL in the message resolves to a denylisted host. */
    KNOWN_BAD_URL,

    /** Mail claims a high-value brand but its authentication (DMARC) is not aligned. */
    MALFORMED_AUTH_BRAND_SPOOF
}
