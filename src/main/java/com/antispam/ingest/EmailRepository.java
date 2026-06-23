package com.antispam.ingest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Append-only access to the immutable {@code emails} table. There is
 * deliberately no update or delete method: the canonical record is written once
 * and only ever read thereafter (the database also enforces this with a trigger,
 * so even a future buggy caller cannot mutate it).
 */
@Repository
public class EmailRepository {

    private static final String INSERT_SQL = """
            insert into emails (
                id, content_hash, raw_content, sender, sender_domain,
                recipients, subject, received_at, auth_results, ingest_source)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (content_hash) do nothing
            """;

    private static final String SELECT_BY_ID_SQL = """
            select id, content_hash, raw_content, sender, sender_domain,
                   recipients, subject, received_at, auth_results,
                   ingest_source, ingested_at
            from emails where id = ?
            """;

    private static final String SELECT_BY_IDS_SQL = """
            select id, content_hash, raw_content, sender, sender_domain,
                   recipients, subject, received_at, auth_results,
                   ingest_source, ingested_at
            from emails where id = any(?)
            """;

    private static final String SELECT_ID_BY_HASH_SQL =
            "select id from emails where content_hash = ?";

    private static final String SELECT_ALL_IDENTITIES_SQL = """
            select id, sender, sender_domain
            from emails
            order by ingested_at, id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public EmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a new email, or returns the existing record's id if identical bytes
     * were already ingested. Idempotent on {@code contentHash}: a duplicate never
     * creates a second canonical row.
     */
    public IngestResult save(byte[] rawContent, byte[] contentHash, ParsedEmail metadata, String source) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(INSERT_SQL,
                id,
                contentHash,
                rawContent,
                metadata.sender(),
                metadata.senderDomain(),
                metadata.recipients(),
                metadata.subject(),
                toTimestamp(metadata.receivedAt()),
                metadata.authResults(),
                source);

        boolean duplicate = inserted == 0;
        UUID canonicalId = duplicate ? findIdByHash(contentHash) : id;
        return new IngestResult(canonicalId, HexFormat.of().formatHex(contentHash), duplicate, source);
    }

    public Optional<Email> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_ID_SQL, EMAIL_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Loads every email whose id is in {@code ids} in a single query — the batch read
     * calibration needs to score a whole split side without N+1 round-trips (story
     * 04.02). Ids with no matching row are simply absent from the result; order is
     * unspecified, so callers key by {@link Email#id()}.
     */
    public List<Email> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement(SELECT_BY_IDS_SQL);
            ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, EMAIL_MAPPER);
    }

    /**
     * The identity of every email in the corpus — id plus the sender fields needed to derive the
     * Kafka partition key — in a stable order ({@code ingested_at}, then {@code id}). Used by replay
     * (story 09.01) to re-publish the immutable corpus to {@code emails.replay} without loading the
     * raw bodies: a replay event carries identity and routing only, and the body is loaded by
     * {@code emailId} at scoring time. The deterministic ordering makes a replay's publish sequence
     * reproducible across runs.
     */
    public List<EmailIdentity> findAllIdentities() {
        return jdbc.query(SELECT_ALL_IDENTITIES_SQL, IDENTITY_MAPPER);
    }

    /**
     * A corpus email's routing identity: its id and the sender fields from which the spine's
     * partition key is derived. Deliberately omits the raw bytes — replay does not need them to
     * publish (story 09.01).
     */
    public record EmailIdentity(UUID id, String sender, String senderDomain) {
    }

    private static final RowMapper<EmailIdentity> IDENTITY_MAPPER = (rs, rowNum) -> new EmailIdentity(
            rs.getObject("id", UUID.class), rs.getString("sender"), rs.getString("sender_domain"));

    private UUID findIdByHash(byte[] contentHash) {
        return jdbc.queryForObject(SELECT_ID_BY_HASH_SQL, UUID.class, contentHash);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static final RowMapper<Email> EMAIL_MAPPER = (rs, rowNum) -> {
        OffsetDateTime receivedAt = rs.getObject("received_at", OffsetDateTime.class);
        OffsetDateTime ingestedAt = rs.getObject("ingested_at", OffsetDateTime.class);
        ParsedEmail metadata = new ParsedEmail(
                rs.getString("sender"),
                rs.getString("sender_domain"),
                rs.getString("recipients"),
                rs.getString("subject"),
                receivedAt == null ? null : receivedAt.toInstant(),
                rs.getString("auth_results"));
        return new Email(
                rs.getObject("id", UUID.class),
                rs.getBytes("content_hash"),
                rs.getBytes("raw_content"),
                metadata,
                rs.getString("ingest_source"),
                ingestedAt == null ? null : ingestedAt.toInstant());
    };
}
