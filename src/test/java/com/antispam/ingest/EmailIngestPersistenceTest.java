package com.antispam.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level guarantees against a real Postgres: byte-faithful storage,
 * idempotent ingest, and database-enforced immutability (UPDATE / DELETE /
 * TRUNCATE on emails are rejected, no matter what code attempts them).
 */
class EmailIngestPersistenceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private JdbcTemplate jdbc;

    private static byte[] sample(String marker) {
        return ("""
                From: alice@example.com
                To: bob@recipient.org
                Subject: Round trip %s
                Date: Tue, 15 Oct 2024 09:30:00 +0000

                Body for %s.
                """.formatted(marker, marker)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void stored_email_round_trips_byte_faithfully_with_parsed_metadata() {
        byte[] raw = sample("faithful");

        IngestResult result = ingestService.ingest(raw, "test");
        Email stored = ingestService.findById(result.emailId()).orElseThrow();

        assertThat(stored.rawContent()).isEqualTo(raw);
        assertThat(stored.metadata().sender()).isEqualTo("alice@example.com");
        assertThat(stored.metadata().senderDomain()).isEqualTo("example.com");
        assertThat(stored.metadata().subject()).isEqualTo("Round trip faithful");
        assertThat(stored.ingestSource()).isEqualTo("test");
        assertThat(stored.ingestedAt()).isNotNull();
    }

    @Test
    void re_ingesting_identical_bytes_is_idempotent_and_returns_existing_id() {
        byte[] raw = sample("dup");

        IngestResult first = ingestService.ingest(raw, "test");
        IngestResult second = ingestService.ingest(raw, "test");

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.emailId()).isEqualTo(first.emailId());

        Integer rows = jdbc.queryForObject(
                "select count(*) from emails where content_hash = ?", Integer.class, sha256(raw));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void update_on_an_email_row_is_rejected_by_the_database() {
        UUID id = ingestService.ingest(sample("noupdate"), "test").emailId();

        assertThatThrownBy(() ->
                jdbc.update("update emails set subject = 'tampered' where id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void delete_on_an_email_row_is_rejected_by_the_database() {
        UUID id = ingestService.ingest(sample("nodelete"), "test").emailId();

        assertThatThrownBy(() ->
                jdbc.update("delete from emails where id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void truncate_of_the_emails_table_is_rejected_by_the_database() {
        ingestService.ingest(sample("notruncate"), "test");

        assertThatThrownBy(() -> jdbc.execute("truncate emails"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
