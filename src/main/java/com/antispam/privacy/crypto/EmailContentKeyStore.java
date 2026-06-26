package com.antispam.privacy.crypto;

import com.antispam.common.JdbcTimestamps;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The mutable per-record key store behind crypto-shredding ({@code email_content_keys}).
 *
 * <p>Each row holds the wrapped data key for one email's body. Unlike the immutable
 * {@code emails} table this one is deliberately mutable in exactly two ways: a
 * record's key is destroyed on erasure ({@link #erase}), and re-wrapped on master-key
 * rotation ({@link #rewrap}). Destroying the key here is what renders the immutable
 * ciphertext unrecoverable without touching the canonical row.
 */
@Repository
public class EmailContentKeyStore {

    private static final String INSERT_SQL = """
            insert into email_content_keys (email_id, wrapped_dek, master_key_version, algorithm)
            values (?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID_SQL = """
            select email_id, wrapped_dek, master_key_version, algorithm, erased_at
            from email_content_keys where email_id = ?
            """;

    private static final String SELECT_BY_IDS_SQL = """
            select email_id, wrapped_dek, master_key_version, algorithm, erased_at
            from email_content_keys where email_id = any(?)
            """;

    private static final String SELECT_REWRAPPABLE_SQL = """
            select email_id, wrapped_dek, master_key_version, algorithm, erased_at
            from email_content_keys where erased_at is null
            """;

    // Guarded by `erased_at is null` so a second erasure is a no-op (0 rows) the caller
    // can report as "already erased" rather than silently re-stamping the timestamp.
    private static final String ERASE_SQL = """
            update email_content_keys
            set wrapped_dek = null, erased_at = now()
            where email_id = ? and erased_at is null
            """;

    private static final String REWRAP_SQL = """
            update email_content_keys
            set wrapped_dek = ?, master_key_version = ?
            where email_id = ? and erased_at is null
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public EmailContentKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the wrapped data key for a newly stored, encrypted email. */
    public void save(UUID emailId, EnvelopeCiphertext sealed, String algorithm) {
        jdbc.update(INSERT_SQL, emailId, sealed.wrappedDek(), sealed.masterKeyVersion(), algorithm);
    }

    public Optional<StoredKey> find(UUID emailId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_ID_SQL, KEY_MAPPER, emailId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Loads keys for many emails at once (batch reads), keyed by email id. */
    public Map<UUID, StoredKey> findAll(Collection<UUID> emailIds) {
        if (emailIds.isEmpty()) {
            return Map.of();
        }
        List<StoredKey> keys = jdbc.query(connection -> {
            var ps = connection.prepareStatement(SELECT_BY_IDS_SQL);
            ps.setArray(1, connection.createArrayOf("uuid", emailIds.toArray()));
            return ps;
        }, KEY_MAPPER);
        Map<UUID, StoredKey> byId = new HashMap<>(keys.size());
        for (StoredKey key : keys) {
            byId.put(key.emailId(), key);
        }
        return byId;
    }

    /**
     * Destroys the wrapped data key for {@code emailId} (crypto-shred). The
     * {@code emails} row is untouched.
     *
     * @return {@link ErasureOutcome#ERASED} on the first erasure,
     *         {@link ErasureOutcome#ALREADY_ERASED} if the key was already destroyed,
     *         {@link ErasureOutcome#NO_KEY} if the email has no key row (stored plaintext)
     */
    public ErasureOutcome erase(UUID emailId) {
        int updated = jdbc.update(ERASE_SQL, emailId);
        if (updated == 1) {
            return ErasureOutcome.ERASED;
        }
        // 0 rows: either no key row exists, or one exists but is already erased.
        return find(emailId).isPresent() ? ErasureOutcome.ALREADY_ERASED : ErasureOutcome.NO_KEY;
    }

    /** Every non-erased key row — the set master-key rotation must re-wrap. */
    public List<StoredKey> findRewrappable() {
        return jdbc.query(SELECT_REWRAPPABLE_SQL, KEY_MAPPER);
    }

    /** Replaces a record's wrapped data key (master-key rotation). No-op on erased rows. */
    public void rewrap(UUID emailId, byte[] newWrappedDek, String newMasterKeyVersion) {
        jdbc.update(REWRAP_SQL, newWrappedDek, newMasterKeyVersion, emailId);
    }

    /**
     * One key-store row.
     *
     * @param wrappedDek the wrapped data key, or {@code null} once the record is erased
     * @param erasedAt   when the key was destroyed, or {@code null} while recoverable
     */
    public record StoredKey(
            UUID emailId, byte[] wrappedDek, String masterKeyVersion, String algorithm, Instant erasedAt) {

        /** Whether this record has been crypto-shredded (its data key destroyed). */
        public boolean isErased() {
            return erasedAt != null;
        }
    }

    private static final RowMapper<StoredKey> KEY_MAPPER = (rs, rowNum) -> new StoredKey(
            rs.getObject("email_id", UUID.class),
            rs.getBytes("wrapped_dek"),
            rs.getString("master_key_version"),
            rs.getString("algorithm"),
            JdbcTimestamps.instantOrNull(rs, "erased_at"));
}
