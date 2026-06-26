package com.antispam.privacy;

/**
 * How aggressively PII is masked before email content leaves our boundary for the
 * external LLM (story 14.03). Configurable per provider, because the right level
 * depends on the provider's data-handling agreement: a provider with a strong DPA
 * (no-training, short retention, or on-prem) may warrant {@link #OFF}, while a
 * general public API warrants {@link #STRICT}.
 */
public enum PiiMaskingLevel {

    /** Mask direct identifiers (addresses, phones, card/account numbers) before egress. */
    STRICT,

    /** Send content unmasked — only for a provider whose DPA makes masking unnecessary. */
    OFF
}
