package com.antispam.privacy.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.antispam.ingest.EmailRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The erasure outcome the bare key store can't determine on its own: telling a
 * missing email apart from an email that exists but was stored unencrypted.
 */
@ExtendWith(MockitoExtension.class)
class ErasureServiceTest {

    @Mock
    private EmailRepository emails;

    @Mock
    private EmailContentKeyStore keyStore;

    @InjectMocks
    private ErasureService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void erased_outcome_passes_straight_through() {
        when(keyStore.erase(id)).thenReturn(ErasureOutcome.ERASED);
        assertThat(service.erase(id)).isEqualTo(ErasureOutcome.ERASED);
    }

    @Test
    void already_erased_outcome_passes_straight_through() {
        when(keyStore.erase(id)).thenReturn(ErasureOutcome.ALREADY_ERASED);
        assertThat(service.erase(id)).isEqualTo(ErasureOutcome.ALREADY_ERASED);
    }

    @Test
    void no_key_for_an_existing_email_means_it_was_stored_unencrypted() {
        when(keyStore.erase(id)).thenReturn(ErasureOutcome.NO_KEY);
        when(emails.existsById(id)).thenReturn(true);
        assertThat(service.erase(id)).isEqualTo(ErasureOutcome.NO_KEY);
    }

    @Test
    void no_key_and_no_such_email_means_not_found() {
        when(keyStore.erase(id)).thenReturn(ErasureOutcome.NO_KEY);
        when(emails.existsById(id)).thenReturn(false);
        assertThat(service.erase(id)).isEqualTo(ErasureOutcome.NOT_FOUND);
    }
}
