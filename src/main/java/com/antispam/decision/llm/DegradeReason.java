package com.antispam.decision.llm;

/**
 * Why an LLM call fail-degraded (story 05.02), used to attribute the degraded fraction in metrics.
 * The two reasons are genuinely different failure modes and are handled differently: a
 * {@link #SCHEMA} failure is a received-but-invalid response and gets the one retry; an
 * {@link #UNAVAILABLE} failure is the provider being off, unconfigured, or unreachable, which the
 * retry cannot fix, so it degrades immediately.
 */
public enum DegradeReason {

    /** Two successive responses failed schema validation. */
    SCHEMA("schema"),

    /** The provider was disabled, unconfigured, or failed at the transport layer. */
    UNAVAILABLE("unavailable");

    private final String tag;

    DegradeReason(String tag) {
        this.tag = tag;
    }

    /** The lowercase metric tag value for this reason. */
    public String tag() {
        return tag;
    }
}
