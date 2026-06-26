package com.antispam.ingest;

import com.antispam.common.JdbcTimestamps;
import com.antispam.privacy.crypto.EmailContentCipher;
import com.antispam.privacy.crypto.EmailContentKeyStore;
import com.antispam.privacy.crypto.EmailContentKeyStore.StoredKey;
import com.antispam.privacy.crypto.EnvelopeCiphertext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only access to the immutable {@code emails} table. There is
 * deliberately no update or delete method: the canonical record is written once
 * and only ever read thereafter (the database also enforces this with a trigger,
 * so even a future buggy caller cannot mutate it).
 *
 * <p><b>Encryption at rest is transparent here (story 14.02).</b> When a master
 * key is configured, {@link #save} encrypts the body before it is stored and
 * records the wrapped data key in {@link EmailContentKeyStore}; the read paths
 * decrypt it back, so every caller sees byte-faithful plaintext and never knows
 * encryption happened. A crypto-shredded (erased) record can no longer be
 * decrypted, so its body reads back as <b>empty bytes</b> — the unambiguous
 * "content erased" signal, since a real stored body is never empty (ingest
 * rejects empty content). Callers test this with {@link Email#contentErased()}.
 */
@Repository
public class EmailRepository {

    /** The content-encryption scheme recorded with each key (see {@link EmailContentCipher}). */
    private static final String ALGORITHM = "AES-256-GCM-ENVELOPE";

    /** Read back from a crypto-shredded record: there is no recoverable body. */
    private static final byte[] ERASED_CONTENT = new byte[0];

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

    private static final String EXISTS_BY_ID_SQL =
            "select exists(select 1 from emails where id = ?)";

    private static final String SELECT_ALL_IDENTITIES_SQL = """
            select id, sender, sender_domain
            from emails
            order by ingested_at, id
            """;

    private final JdbcTemplate jdbc;
    private final EmailContentCipher cipher;
    private final EmailContentKeyStore keyStore;

    @Autowired
    public EmailRepository(JdbcTemplate jdbc, EmailContentCipher cipher, EmailContentKeyStore keyStore) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.keyStore = keyStore;
    }

    /**
     * Stores a new email, or returns the existing record's id if identical bytes
     * were already ingested. Idempotent on {@code contentHash}: a duplicate never
     * creates a second canonical row.
     *
     * <p>{@code contentHash} is the hash of the <i>plaintext</i> (computed by the
     * caller before encryption), so idempotency is unaffected by the random IV/data
     * key that makes each encryption's ciphertext differ. The email row and its
     * wrapped-key row are written in one transaction so a body can never be stored
     * without a key to open it.
     */
    @Transactional
    public IngestResult save(byte[] rawContent, byte[] contentHash, ParsedEmail metadata, String source) {
        UUID id = UUID.randomUUID();
        EnvelopeCiphertext sealed = cipher.isEnabled() ? cipher.encrypt(rawContent) : null;
        byte[] storedContent = sealed == null ? rawContent : sealed.ciphertext();

        int inserted = jdbc.update(INSERT_SQL,
                id,
                contentHash,
                storedContent,
                metadata.sender(),
                metadata.senderDomain(),
                metadata.recipients(),
                metadata.subject(),
                toTimestamp(metadata.receivedAt()),
                metadata.authResults(),
                source);

        boolean duplicate = inserted == 0;
        // Only the row we actually inserted gets a key row; a duplicate's key already exists.
        if (!duplicate && sealed != null) {
            keyStore.save(id, sealed, ALGORITHM);
        }
        UUID canonicalId = duplicate ? findIdByHash(contentHash) : id;
        return new IngestResult(canonicalId, HexFormat.of().formatHex(contentHash), duplicate, source);
    }

    public Optional<Email> findById(UUID id) {
        try {
            StoredEmail stored = jdbc.queryForObject(SELECT_BY_ID_SQL, STORED_MAPPER, id);
            return Optional.of(decode(stored, keyStore.find(id).orElse(null)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Whether an email with this id exists. Cheap existence check (no body decrypt). */
    public boolean existsById(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EXISTS_BY_ID_SQL, Boolean.class, id));
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
        List<StoredEmail> stored = jdbc.query(connection -> {
            var ps = connection.prepareStatement(SELECT_BY_IDS_SQL);
            ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, STORED_MAPPER);
        Map<UUID, StoredKey> keys = keyStore.findAll(stored.stream().map(StoredEmail::id).toList());
        return stored.stream().map(s -> decode(s, keys.get(s.id()))).toList();
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

    /**
     * Turns a stored row plus its key (if any) into the plaintext {@link Email}.
     *
     * <ul>
     *   <li>no key row → the body was stored as plaintext; return it verbatim.</li>
     *   <li>erased key → the body is crypto-shredded; return empty bytes.</li>
     *   <li>live key → decrypt the ciphertext back to byte-faithful plaintext.</li>
     * </ul>
     */
    private Email decode(StoredEmail stored, StoredKey key) {
        byte[] body;
        if (key == null) {
            body = stored.atRestContent();
        } else if (key.isErased()) {
            body = ERASED_CONTENT;
        } else {
            body = cipher.decrypt(stored.atRestContent(), key.wrappedDek(), key.masterKeyVersion());
        }
        return new Email(stored.id(), stored.contentHash(), body, stored.metadata(),
                stored.ingestSource(), stored.ingestedAt());
    }

    /** A row as stored: {@code atRestContent} is ciphertext when encrypted, plaintext otherwise. */
    private record StoredEmail(
            UUID id, byte[] contentHash, byte[] atRestContent, ParsedEmail metadata,
            String ingestSource, Instant ingestedAt) {
    }

    private static final RowMapper<StoredEmail> STORED_MAPPER = (rs, rowNum) -> {
        ParsedEmail metadata = new ParsedEmail(
                rs.getString("sender"),
                rs.getString("sender_domain"),
                rs.getString("recipients"),
                rs.getString("subject"),
                JdbcTimestamps.instantOrNull(rs, "received_at"),
                rs.getString("auth_results"));
        return new StoredEmail(
                rs.getObject("id", UUID.class),
                rs.getBytes("content_hash"),
                rs.getBytes("raw_content"),
                metadata,
                rs.getString("ingest_source"),
                JdbcTimestamps.instantOrNull(rs, "ingested_at"));
    };
}
