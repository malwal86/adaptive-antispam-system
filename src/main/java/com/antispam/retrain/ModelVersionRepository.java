package com.antispam.retrain;

import com.antispam.common.JdbcTimestamps;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The model registry's persistence (story 10.04): registers a promoted model_version with its
 * provenance and reads it back for audit/observability. {@link #register} is an upsert keyed on
 * version so re-running a promotion for the same candidate is idempotent — registering twice records
 * the latest provenance rather than failing on the primary key.
 */
@Repository
public class ModelVersionRepository {

    private static final String UPSERT_SQL = """
            insert into model_versions (version, artifact_uri, gate_precision, source_run, promoted_by)
            values (?, ?, ?, ?, ?)
            on conflict (version) do update set
                artifact_uri = excluded.artifact_uri,
                gate_precision = excluded.gate_precision,
                source_run = excluded.source_run,
                promoted_by = excluded.promoted_by,
                promoted_at = now()
            returning promoted_at
            """;

    private static final String SELECT_BY_VERSION_SQL = """
            select version, artifact_uri, gate_precision, source_run, promoted_by, promoted_at
            from model_versions
            where version = ?
            """;

    private static final String SELECT_LATEST_SQL = """
            select version, artifact_uri, gate_precision, source_run, promoted_by, promoted_at
            from model_versions
            order by promoted_at desc, version
            limit 1
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public ModelVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registers (or re-registers) a promoted model_version and returns it with its assigned
     * {@code promoted_at}.
     */
    public ModelVersionRecord register(ModelVersionRecord record) {
        Instant promotedAt = jdbc.queryForObject(UPSERT_SQL,
                (rs, rowNum) -> JdbcTimestamps.instantOrNull(rs, "promoted_at"),
                record.version(), record.artifactUri(), record.gatePrecision(),
                record.sourceRun(), record.promotedBy());
        return new ModelVersionRecord(record.version(), record.artifactUri(), record.gatePrecision(),
                record.sourceRun(), record.promotedBy(),
                promotedAt);
    }

    public Optional<ModelVersionRecord> findByVersion(String version) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_VERSION_SQL, MAPPER, version));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** The most recently promoted model_version, or empty if none has ever been promoted. */
    public Optional<ModelVersionRecord> findLatest() {
        return jdbc.query(SELECT_LATEST_SQL, MAPPER).stream().findFirst();
    }

    private static final RowMapper<ModelVersionRecord> MAPPER = (rs, rowNum) -> {
        Double gatePrecision = (Double) rs.getObject("gate_precision");
        return new ModelVersionRecord(
                rs.getString("version"),
                rs.getString("artifact_uri"),
                gatePrecision,
                rs.getObject("source_run", UUID.class),
                rs.getString("promoted_by"),
                JdbcTimestamps.instantOrNull(rs, "promoted_at"));
    };
}
