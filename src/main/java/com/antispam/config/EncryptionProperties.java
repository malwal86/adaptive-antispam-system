package com.antispam.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Master-key material for encrypting email bodies at rest (story 14.02), bound
 * from the {@code antispam.encryption} prefix. Keys are base64-encoded AES master
 * keys, supplied through the environment/secret manager and <b>never</b> the repo:
 *
 * <ul>
 *   <li>{@code ANTISPAM_ENCRYPTION_KEYS_V1=<base64 32-byte key>} → {@code keys.v1}</li>
 *   <li>{@code ANTISPAM_ENCRYPTION_ACTIVE_KEY_VERSION=v1} → {@code activeKeyVersion}</li>
 * </ul>
 *
 * <p><b>Rotation</b> is additive: introduce {@code keys.v2}, set
 * {@code activeKeyVersion=v2}, and keep {@code keys.v1} until every record's
 * wrapped data key has been re-wrapped under v2 (see {@code KeyRotationService}).
 * Holding both versions at once is what keeps existing records decryptable across
 * the rotation.
 *
 * <p>Deliberately <b>optional</b>, like {@link SupabaseStorageProperties}: with no
 * keys configured the cipher is disabled and bodies are stored as today's
 * plaintext, so local dev, the test profile, and a keyless deploy all boot
 * unchanged. Encryption (and therefore crypto-shred erasure) engages only once a
 * key and active version are supplied — which the hosted deploy does.
 *
 * @param keys             base64-encoded master keys keyed by version
 * @param activeKeyVersion the version used to wrap newly written records
 */
@ConfigurationProperties(prefix = "antispam.encryption")
public record EncryptionProperties(Map<String, String> keys, String activeKeyVersion) {

    public EncryptionProperties {
        keys = keys == null ? Map.of() : keys;
    }

    /** Whether a master key is configured (encryption and erasure are then active). */
    public boolean isConfigured() {
        return !keys.isEmpty();
    }
}
