package com.antispam.decision;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Append-only access to the {@code classifications} table. A decision is written
 * once with {@link #save}; re-evaluations are new rows, never updates. Reads are
 * by the email the decision concerns ({@link #findByEmailId}), which is what the
 * analyzer UI (01.05) needs to show an email's verdict.
 */
@Repository
public class ClassificationRepository {

    private static final String INSERT_SQL = """
            insert into classifications (
                id, email_id, decision, reason_codes, route_used, latency_ms,
                spam_score, phishing_score, model_version)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning created_at
            """;

    private static final String SELECT_BY_EMAIL_SQL = """
            select id, email_id, decision, reason_codes, route_used, latency_ms,
                   spam_score, phishing_score, model_version, created_at
            from classifications
            where email_id = ?
            order by created_at
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public ClassificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists {@code outcome} as a decision about {@code emailId} and returns the
     * stored {@link Classification}, including its generated id and timestamp.
     */
    public Classification save(UUID emailId, DecisionOutcome outcome) {
        UUID id = UUID.randomUUID();
        String[] codeNames = outcome.reasonCodes().stream().map(Enum::name).toArray(String[]::new);
        ModelScores scores = outcome.scores();

        OffsetDateTime createdAt = jdbc.query(connection -> {
            var ps = connection.prepareStatement(INSERT_SQL);
            ps.setObject(1, id);
            ps.setObject(2, emailId);
            ps.setString(3, outcome.decision().name());
            ps.setArray(4, connection.createArrayOf("text", codeNames));
            ps.setString(5, outcome.route().name());
            ps.setLong(6, outcome.latencyMs());
            // Scores are present only on the model route; a hard-rule row stores NULLs.
            if (scores == null) {
                ps.setNull(7, java.sql.Types.DOUBLE);
                ps.setNull(8, java.sql.Types.DOUBLE);
                ps.setNull(9, java.sql.Types.VARCHAR);
            } else {
                ps.setDouble(7, scores.spamScore());
                ps.setDouble(8, scores.phishingScore());
                ps.setString(9, scores.modelVersion());
            }
            return ps;
        }, rs -> {
            rs.next();
            return rs.getObject("created_at", OffsetDateTime.class);
        });

        return new Classification(
                id, emailId, outcome.decision(), outcome.reasonCodes(),
                outcome.route(), outcome.latencyMs(), scores, createdAt.toInstant());
    }

    public List<Classification> findByEmailId(UUID emailId) {
        return jdbc.query(SELECT_BY_EMAIL_SQL, CLASSIFICATION_MAPPER, emailId);
    }

    private static final RowMapper<Classification> CLASSIFICATION_MAPPER = (rs, rowNum) -> {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new Classification(
                rs.getObject("id", UUID.class),
                rs.getObject("email_id", UUID.class),
                Decision.valueOf(rs.getString("decision")),
                reasonCodes(rs.getArray("reason_codes")),
                RouteUsed.valueOf(rs.getString("route_used")),
                rs.getLong("latency_ms"),
                modelScores(rs),
                createdAt == null ? null : createdAt.toInstant());
    };

    /**
     * Reconstructs the model scores, or {@code null} for a hard-rule row. The
     * {@code model_version} column is the discriminator: it is non-null exactly
     * when the model ran, so a row missing it carries no scores.
     */
    private static ModelScores modelScores(java.sql.ResultSet rs) throws java.sql.SQLException {
        String modelVersion = rs.getString("model_version");
        if (modelVersion == null) {
            return null;
        }
        return new ModelScores(
                rs.getDouble("spam_score"), rs.getDouble("phishing_score"), modelVersion);
    }

    private static List<ReasonCode> reasonCodes(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        String[] names = (String[]) array.getArray();
        return List.of(names).stream().map(ReasonCode::valueOf).toList();
    }
}
