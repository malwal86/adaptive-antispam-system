package com.antispam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared secret that authorizes privileged, unredacted access to email PII
 * (story 14.05) — {@code ?reveal=true}, {@code /emails/{id}/raw}, and erasure. Bound
 * from {@code antispam.privacy.reveal}; the secret comes only from the
 * environment/secret manager, never the repo:
 *
 * <pre>ANTISPAM_PRIVACY_REVEAL_SECRET=&lt;a strong shared secret&gt;</pre>
 *
 * <p><b>Fail closed.</b> Unlike the other optional knobs, a blank secret does not mean
 * "open" — it means the privileged accessors are <b>denied</b> outright (there is no way
 * to present a matching credential). A deployment that wants reveal/raw/erasure must set
 * the secret deliberately; forgetting to set it cannot silently expose PII. The masked
 * default reads need no secret and are unaffected.
 *
 * @param secret the shared bearer secret, or blank to deny all privileged access
 */
@ConfigurationProperties(prefix = "antispam.privacy.reveal")
public record RevealAccessProperties(String secret) {

    /** Whether a reveal secret is configured (privileged access is possible at all). */
    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }
}
