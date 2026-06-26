package com.antispam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The secret used to pseudonymize sender identities in de-identified exports
 * (story 14.04), bound from {@code antispam.privacy.export}. The key is a base64
 * HMAC key supplied <b>only</b> through the environment/secret manager, never the
 * repo:
 *
 * <pre>ANTISPAM_PRIVACY_EXPORT_PSEUDONYM_KEY=&lt;base64 HMAC key&gt;</pre>
 *
 * <p>Optional, like the encryption and Supabase keys: when unset, the export still
 * de-identifies, but with a process-stable random key (pseudonyms are then consistent
 * within a run but not across restarts). A real deploy sets the key so the sender
 * pseudonym — and therefore grouped-split and reputation lineage — stays stable over
 * time. The key is never written to the repo.
 *
 * @param pseudonymKey base64-encoded HMAC key, or blank to use a process-stable random key
 */
@ConfigurationProperties(prefix = "antispam.privacy.export")
public record ExportPrivacyProperties(String pseudonymKey) {

    /** Whether a stable pseudonymization key is configured. */
    public boolean hasPseudonymKey() {
        return pseudonymKey != null && !pseudonymKey.isBlank();
    }
}
