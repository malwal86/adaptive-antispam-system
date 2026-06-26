package com.antispam.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.privacy.crypto.EmailContentCipher;
import com.antispam.privacy.crypto.EmailContentCipherConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Pins down that the master keys bind from the {@code antispam.encryption.keys.<version>}
 * property shape the deploy/env and the integration test rely on — map binding is the one
 * piece of this story that can't be exercised by the (locally-skipped) Testcontainers IT,
 * so it gets its own deterministic check here.
 */
class EncryptionPropertiesBindingTest {

    private static final String KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void binds_versioned_keys_into_the_map_and_builds_an_enabled_cipher() {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "antispam.encryption.active-key-version", "test",
                "antispam.encryption.keys.test", KEY_B64));

        EncryptionProperties properties =
                new Binder(source).bind("antispam.encryption", EncryptionProperties.class).get();

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.keys()).containsKey("test");
        assertThat(properties.activeKeyVersion()).isEqualTo("test");

        EmailContentCipher cipher = new EmailContentCipherConfig().emailContentCipher(properties);
        assertThat(cipher.isEnabled()).isTrue();
        assertThat(cipher.activeKeyVersion()).isEqualTo("test");
    }

    @Test
    void absent_keys_bind_to_a_disabled_cipher() {
        EncryptionProperties properties = new EncryptionProperties(null, null);

        assertThat(properties.isConfigured()).isFalse();
        assertThat(new EmailContentCipherConfig().emailContentCipher(properties).isEnabled()).isFalse();
    }
}
