package com.antispam.retrain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Appends and reads the promotion/rollback audit log (story 10.04 AC 5). Every activation change the
 * retrain loop makes is one row; the id is assigned by the caller (mirroring {@code retrain_labels})
 * and the timestamp by the database. Reads are newest-first — the question the log answers is "what was
 * promoted/rolled back recently, by whom".
 */
@Repository
public class ModelActivationAuditRepository {

    private static final String INSERT_SQL = """
            insert into model_activation_audit (id, action, policy_version, model_version, actor)
            values (?, ?, ?, ?, ?)
            returning at
            """;

    private static final String SELECT_RECENT_SQL = """
            select id, action, policy_version, model_version, actor, at
            from model_activation_audit
            order by at desc, id
            limit ?
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public ModelActivationAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends one activation-change entry and returns it with its assigned id and timestamp.
     */
    public ModelActivationAudit record(ModelActivationAction action, String policyVersion,
            String modelVersion, String actor) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = jdbc.queryForObject(INSERT_SQL,
                (rs, rowNum) -> rs.getObject("at", OffsetDateTime.class),
                id, action.dbValue(), policyVersion, modelVersion, actor);
        return new ModelActivationAudit(id, action, policyVersion, modelVersion, actor,
                at == null ? null : at.toInstant());
    }

    /** The most recent activation changes, newest first. */
    public List<ModelActivationAudit> recent(int limit) {
        return jdbc.query(SELECT_RECENT_SQL, MAPPER, limit);
    }

    private static final RowMapper<ModelActivationAudit> MAPPER = (rs, rowNum) -> {
        OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
        return new ModelActivationAudit(
                rs.getObject("id", UUID.class),
                ModelActivationAction.fromDbValue(rs.getString("action")),
                rs.getString("policy_version"),
                rs.getString("model_version"),
                rs.getString("actor"),
                at == null ? null : at.toInstant());
    };
}
