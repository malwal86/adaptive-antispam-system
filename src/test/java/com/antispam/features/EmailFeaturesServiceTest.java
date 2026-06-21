package com.antispam.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.idempotency.ProcessedMessageLedger;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The idempotency contract of feature extraction (story 02.03), isolated from the
 * database: the service extracts and stores exactly once per email, claims the
 * processed-message ledger before doing so, and treats a lost claim (a redelivery)
 * as a no-op. The ledger, repositories, and extractor are mocked — this is about the
 * dedupe decision and its ordering, not persistence (covered end-to-end in
 * {@code IdempotentProcessingIntegrationTest}).
 */
@ExtendWith(MockitoExtension.class)
class EmailFeaturesServiceTest {

    private static final UUID EMAIL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    // Content is irrelevant here — the extractor is mocked — so a bare id suffices.
    private static final Email EMAIL = new Email(EMAIL_ID, null, null, null, "api", null);
    private static final EmailFeatures EXTRACTED = new EmailFeatures(EMAIL_ID, 1, null, null);

    @Mock
    private EmailRepository emails;

    @Mock
    private EmailFeaturesRepository features;

    @Mock
    private EmailFeatureExtractor extractor;

    @Mock
    private ProcessedMessageLedger ledger;

    private EmailFeaturesService service;

    @BeforeEach
    void setUp() {
        service = new EmailFeaturesService(emails, features, extractor, ledger);
    }

    @Test
    void first_delivery_extracts_and_stores_the_features() {
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.of(EMAIL));
        when(ledger.claim(EmailFeaturesService.CONSUMER_GROUP, EMAIL_ID.toString())).thenReturn(true);
        when(extractor.extract(EMAIL)).thenReturn(EXTRACTED);

        Optional<EmailFeatures> result = service.extractAndStore(EMAIL_ID);

        assertThat(result).contains(EXTRACTED);
        verify(features).save(EXTRACTED);
    }

    @Test
    void a_redelivery_neither_extracts_nor_stores_again() {
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.of(EMAIL));
        when(ledger.claim(EmailFeaturesService.CONSUMER_GROUP, EMAIL_ID.toString())).thenReturn(false);

        Optional<EmailFeatures> result = service.extractAndStore(EMAIL_ID);

        assertThat(result).isEmpty();
        verify(extractor, never()).extract(any());
        verify(features, never()).save(any());
    }

    @Test
    void an_unknown_email_is_skipped_without_claiming_the_ledger() {
        // Claiming a not-yet-visible email would wrongly suppress its later, valid
        // delivery — so the not-found branch must leave the ledger untouched.
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.empty());

        Optional<EmailFeatures> result = service.extractAndStore(EMAIL_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(ledger, extractor, features);
    }
}
