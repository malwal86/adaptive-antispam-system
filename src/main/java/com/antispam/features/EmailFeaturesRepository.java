package com.antispam.features;

import com.antispam.common.JdbcTimestamps;
import com.antispam.common.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the versioned {@code email_features} table. A row is keyed by
 * {@code (email_id, feature_version)}; the {@link FeatureSet} payload is stored as
 * JSONB so the feature set can grow without a schema migration.
 *
 * <p>{@link #save} is an upsert on that key: re-extracting an email at the same
 * version refreshes the payload in place (extraction is deterministic, so the
 * payload is identical anyway) and never creates a duplicate. This is defense in
 * depth — the consumer is kept from re-running at all by the processed-message
 * ledger (story 02.03, {@code com.antispam.idempotency.ProcessedMessageLedger}) —
 * but it keeps the row convergent even if a write reaches the table by another path.
 */
@Repository
public class EmailFeaturesRepository {

    private static final String UPSERT_SQL = """
            insert into email_features (email_id, feature_version, features)
            values (?, ?, ?::jsonb)
            on conflict (email_id, feature_version)
            do update set features = excluded.features, extracted_at = now()
            """;

    private static final String SELECT_SQL = """
            select email_id, feature_version, features, extracted_at
            from email_features
            where email_id = ? and feature_version = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public EmailFeaturesRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Inserts or refreshes the features row for {@code (emailId, featureVersion)}. */
    public void save(EmailFeatures features) {
        jdbc.update(UPSERT_SQL,
                features.emailId(),
                features.featureVersion(),
                toJson(features.features()));
    }

    /** Returns the features for an email at a specific version, if present. */
    public Optional<EmailFeatures> find(UUID emailId, int featureVersion) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject(SELECT_SQL, mapper, emailId, featureVersion));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String toJson(FeatureSet features) {
        return JsonCodec.serialize(objectMapper, features, "feature set");
    }

    private final RowMapper<EmailFeatures> mapper = (rs, rowNum) -> {
        FeatureSet features = fromJson(rs.getString("features"));
        return new EmailFeatures(
                rs.getObject("email_id", UUID.class),
                rs.getInt("feature_version"),
                features,
                JdbcTimestamps.instantOrNull(rs, "extracted_at"));
    };

    private FeatureSet fromJson(String json) {
        try {
            return objectMapper.readValue(json, FeatureSet.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize feature set", e);
        }
    }
}
