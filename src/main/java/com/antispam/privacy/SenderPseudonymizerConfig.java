package com.antispam.privacy;

import com.antispam.config.ExportPrivacyProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link SenderPseudonymizer} from {@link ExportPrivacyProperties}.
 *
 * <p>With a key configured, the pseudonym is stable across runs and instances — the
 * property grouped-split and reputation lineage rely on. With no key configured, a
 * process-stable random key is generated so the export still de-identifies (and is
 * self-consistent within a run); a warning makes the missing-key state visible, since
 * pseudonyms then won't line up across restarts. The key is never read from the repo.
 */
@Configuration
public class SenderPseudonymizerConfig {

    private static final Logger log = LoggerFactory.getLogger(SenderPseudonymizerConfig.class);
    private static final int RANDOM_KEY_BYTES = 32;

    @Bean
    public SenderPseudonymizer senderPseudonymizer(ExportPrivacyProperties properties) {
        if (properties.hasPseudonymKey()) {
            return new SenderPseudonymizer(decode(properties.pseudonymKey()));
        }
        log.warn("no antispam.privacy.export.pseudonym-key configured; using a process-stable "
                + "random key — export pseudonyms are consistent within this run but not across restarts");
        byte[] randomKey = new byte[RANDOM_KEY_BYTES];
        new SecureRandom().nextBytes(randomKey);
        return new SenderPseudonymizer(randomKey);
    }

    private static byte[] decode(String base64Key) {
        try {
            return Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "antispam.privacy.export.pseudonym-key is not valid base64", e);
        }
    }
}
