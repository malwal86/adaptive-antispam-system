package com.antispam.privacy.crypto;

import com.antispam.config.EncryptionProperties;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the singleton {@link EmailContentCipher} from {@link EncryptionProperties}.
 * Decoding the base64 master keys happens once here, at startup, so a malformed key
 * fails the boot loudly rather than at the first ingest. With no keys configured the
 * cipher is constructed disabled (empty key map), and bodies are stored as plaintext.
 */
@Configuration
public class EmailContentCipherConfig {

    @Bean
    public EmailContentCipher emailContentCipher(EncryptionProperties properties) {
        Map<String, byte[]> keys = properties.keys().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> decode(e.getKey(), e.getValue())));
        return new EmailContentCipher(keys, properties.activeKeyVersion());
    }

    private static byte[] decode(String version, String base64Key) {
        try {
            return Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "antispam.encryption.keys." + version + " is not valid base64", e);
        }
    }
}
