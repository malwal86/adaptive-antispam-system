package com.antispam.privacy.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives the envelope cipher's contract: a byte-faithful encrypt/decrypt
 * round-trip, authenticated failure on a wrong/destroyed/tampered key, and a
 * master-key re-wrap path that keeps existing records readable across rotation.
 */
class EmailContentCipherTest {

    private static final byte[] KEY_V1 = key((byte) 1);
    private static final byte[] KEY_V2 = key((byte) 2);

    private final EmailContentCipher cipher =
            new EmailContentCipher(Map.of("v1", KEY_V1, "v2", KEY_V2), "v1");

    @Test
    void encrypt_then_decrypt_recovers_the_exact_plaintext_bytes() {
        byte[] plaintext = "From: alice@example.com\r\n\r\nhello".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext sealed = cipher.encrypt(plaintext);
        byte[] recovered = cipher.decrypt(sealed.ciphertext(), sealed.wrappedDek(), sealed.masterKeyVersion());

        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void ciphertext_is_not_the_plaintext_and_carries_the_active_key_version() {
        byte[] plaintext = "secret body".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext sealed = cipher.encrypt(plaintext);

        assertThat(sealed.ciphertext()).isNotEqualTo(plaintext);
        assertThat(sealed.masterKeyVersion()).isEqualTo("v1");
    }

    @Test
    void each_encryption_uses_a_fresh_data_key_and_iv_so_ciphertexts_differ() {
        byte[] plaintext = "same input".getBytes(StandardCharsets.UTF_8);

        EnvelopeCiphertext a = cipher.encrypt(plaintext);
        EnvelopeCiphertext b = cipher.encrypt(plaintext);

        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(a.wrappedDek()).isNotEqualTo(b.wrappedDek());
    }

    @Test
    void decrypt_fails_when_the_data_key_is_destroyed() {
        EnvelopeCiphertext sealed = cipher.encrypt("body".getBytes(StandardCharsets.UTF_8));

        // Crypto-shredding destroys the wrapped data key; the ciphertext alone is
        // then unrecoverable, which is the whole point of erasure-by-key-destruction.
        assertThatThrownBy(() -> cipher.decrypt(sealed.ciphertext(), new byte[0], sealed.masterKeyVersion()))
                .isInstanceOf(ContentDecryptionException.class);
    }

    @Test
    void decrypt_fails_when_the_ciphertext_is_tampered_with() {
        EnvelopeCiphertext sealed = cipher.encrypt("body".getBytes(StandardCharsets.UTF_8));
        byte[] tampered = sealed.ciphertext().clone();
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(tampered, sealed.wrappedDek(), sealed.masterKeyVersion()))
                .isInstanceOf(ContentDecryptionException.class);
    }

    @Test
    void decrypt_fails_for_an_unknown_master_key_version() {
        EnvelopeCiphertext sealed = cipher.encrypt("body".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> cipher.decrypt(sealed.ciphertext(), sealed.wrappedDek(), "v99"))
                .isInstanceOf(ContentDecryptionException.class);
    }

    @Test
    void rewrap_under_a_new_active_master_key_keeps_old_records_decryptable() {
        EnvelopeCiphertext sealed = cipher.encrypt("body".getBytes(StandardCharsets.UTF_8));

        EmailContentCipher rotated = new EmailContentCipher(Map.of("v1", KEY_V1, "v2", KEY_V2), "v2");
        byte[] rewrapped = rotated.rewrap(sealed.wrappedDek(), sealed.masterKeyVersion());

        // The content ciphertext never changes (the immutable row is untouched); only
        // the wrapped data key is re-encrypted under the new active master key.
        byte[] recovered = rotated.decrypt(sealed.ciphertext(), rewrapped, rotated.activeKeyVersion());
        assertThat(rotated.activeKeyVersion()).isEqualTo("v2");
        assertThat(recovered).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_cipher_with_no_keys_is_disabled_and_refuses_to_encrypt() {
        EmailContentCipher disabled = new EmailContentCipher(Map.of(), null);

        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_cipher_with_keys_is_enabled() {
        assertThat(cipher.isEnabled()).isTrue();
    }

    private static byte[] key(byte fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, fill);
        // Mix in a little so the two test keys aren't trivially related.
        new SecureRandom(new byte[] {fill}).nextBytes(k);
        return k;
    }
}
