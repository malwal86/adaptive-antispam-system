package com.antispam.decision.llm;

import com.antispam.privacy.PiiMaskingLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The PII-masking level applied to email content before it is sent to the LLM
 * provider (story 14.03), bound from {@code antispam.llm.pii-masking}. Kept separate
 * from {@link LlmProperties} because it is a privacy/egress concern, not a fallback
 * tuning knob, and because the right level is a per-provider decision driven by the
 * provider's data-handling agreement.
 *
 * <p>Defaults to {@link PiiMaskingLevel#STRICT} — the safe default is to scrub
 * identifiers before any third-party egress; a deploy lowers it only for a provider
 * whose DPA makes masking unnecessary ({@code ANTISPAM_LLM_PII_MASKING_LEVEL=OFF}).
 *
 * @param level the masking level applied to outbound prompt content
 */
@ConfigurationProperties(prefix = "antispam.llm.pii-masking")
public record LlmPiiMaskingProperties(PiiMaskingLevel level) {

    public LlmPiiMaskingProperties {
        level = level == null ? PiiMaskingLevel.STRICT : level;
    }
}
