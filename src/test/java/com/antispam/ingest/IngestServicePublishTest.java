package com.antispam.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.event.RawEmailEvent;
import com.antispam.event.RawEmailPublisher;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level contract for ingest's event publishing: a newly persisted email
 * produces exactly one {@code emails.raw} event keyed by sender, and a duplicate
 * ingest produces none (the event was already emitted on first ingest, so this
 * preserves "exactly one message per email"). The repository and parser are
 * mocked — this test is about the publish decision, not persistence.
 */
@ExtendWith(MockitoExtension.class)
class IngestServicePublishTest {

    @Mock
    private EmailRepository repository;

    @Mock
    private EmailParser parser;

    @Mock
    private RawEmailPublisher publisher;

    private IngestService service;

    private static final byte[] RAW = "From: News@Example.com\nSubject: hi\n\nbody\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final ParsedEmail METADATA =
            new ParsedEmail("News@Example.com", "example.com", null, "hi", null, null);

    @BeforeEach
    void setUp() {
        service = new IngestService(repository, parser, publisher);
    }

    @Test
    void publishes_one_event_keyed_by_sender_after_a_new_email_is_persisted() {
        when(parser.parse(any())).thenReturn(METADATA);
        UUID id = UUID.randomUUID();
        when(repository.save(any(), any(), any(), any()))
                .thenReturn(new IngestResult(id, "abc123", false, "api"));

        service.ingest(RAW, "api");

        ArgumentCaptor<RawEmailEvent> captor = ArgumentCaptor.forClass(RawEmailEvent.class);
        verify(publisher).publish(captor.capture());
        RawEmailEvent event = captor.getValue();
        assertThat(event.emailId()).isEqualTo(id);
        assertThat(event.contentHashHex()).isEqualTo("abc123");
        assertThat(event.ingestSource()).isEqualTo("api");
        // Sender identity is normalized into a stable partition key.
        assertThat(event.senderKey()).isEqualTo("news@example.com");
        assertThat(event.schemaVersion()).isEqualTo(RawEmailEvent.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void does_not_publish_when_the_ingest_is_a_duplicate() {
        when(parser.parse(any())).thenReturn(METADATA);
        when(repository.save(any(), any(), any(), any()))
                .thenReturn(new IngestResult(UUID.randomUUID(), "abc123", true, "api"));

        service.ingest(RAW, "api");

        verify(publisher, never()).publish(any());
    }

    @Test
    void rejects_empty_content_without_persisting_or_publishing() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.ingest(new byte[0], "api"));
        verifyNoInteractions(repository, publisher);
    }
}
