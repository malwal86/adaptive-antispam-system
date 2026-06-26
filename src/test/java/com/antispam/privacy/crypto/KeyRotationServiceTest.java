package com.antispam.privacy.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.privacy.crypto.EmailContentKeyStore.StoredKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Rotation re-wraps live data keys under the new active master key and leaves the
 * content ciphertext alone, so records stay decryptable across the rotation.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationServiceTest {

    private static final byte[] KEY_V1 = filled((byte) 7);
    private static final byte[] KEY_V2 = filled((byte) 9);

    @Mock
    private EmailContentKeyStore keyStore;

    @Test
    void rewraps_a_v1_record_under_the_active_v2_key_and_keeps_it_decryptable() {
        // A record sealed while v1 was active.
        EmailContentCipher v1Active = new EmailContentCipher(Map.of("v1", KEY_V1), "v1");
        EnvelopeCiphertext sealed = v1Active.encrypt("body".getBytes(StandardCharsets.UTF_8));
        UUID id = UUID.randomUUID();
        when(keyStore.findRewrappable()).thenReturn(List.of(
                new StoredKey(id, sealed.wrappedDek(), "v1", "AES-256-GCM-ENVELOPE", null)));

        // Now v2 is active (v1 still held for the rotation).
        EmailContentCipher v2Active = new EmailContentCipher(Map.of("v1", KEY_V1, "v2", KEY_V2), "v2");
        KeyRotationService service = new KeyRotationService(v2Active, keyStore);

        int count = service.rotate();

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<byte[]> newWrap = ArgumentCaptor.forClass(byte[].class);
        verify(keyStore).rewrap(org.mockito.ArgumentMatchers.eq(id), newWrap.capture(),
                org.mockito.ArgumentMatchers.eq("v2"));
        // The re-wrapped key opens the SAME content ciphertext under v2.
        byte[] recovered = v2Active.decrypt(sealed.ciphertext(), newWrap.getValue(), "v2");
        assertThat(recovered).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void leaves_records_already_on_the_active_key_untouched() {
        EmailContentCipher v2Active = new EmailContentCipher(Map.of("v1", KEY_V1, "v2", KEY_V2), "v2");
        EnvelopeCiphertext sealed = v2Active.encrypt("body".getBytes(StandardCharsets.UTF_8));
        when(keyStore.findRewrappable()).thenReturn(List.of(
                new StoredKey(UUID.randomUUID(), sealed.wrappedDek(), "v2", "AES-256-GCM-ENVELOPE", null)));

        int count = new KeyRotationService(v2Active, keyStore).rotate();

        assertThat(count).isZero();
        verify(keyStore, never()).rewrap(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static byte[] filled(byte b) {
        byte[] k = new byte[32];
        Arrays.fill(k, b);
        return k;
    }
}
