package com.antispam.decision;

import java.math.BigDecimal;
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
                spam_score, phishing_score, model_version, calibrated_confidence,
                posterior, uncertainty_band, policy_version, llm_cost_usd)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning created_at
            """;

    private static final String SELECT_BY_EMAIL_SQL = """
            select id, email_id, decision, reason_codes, route_used, latency_ms,
                   spam_score, phishing_score, model_version, calibrated_confidence,
                   posterior, uncertainty_band, policy_version, llm_cost_usd, created_at
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
     * Persists {@code outcome} (with its optional reputation-fused {@code fused} score)
     * as a decision about {@code emailId} and returns the stored {@link Classification},
     * including its generated id and timestamp.
     *
     * @param fused the fused posterior, or {@code null} when the decision was not fused
     *              (a hard-rule short-circuit, or a model row scored before any calibration
     *              was installed — story 04.04)
     * @param policyVersion the active policy the decision was made under (story 04.05);
     *              {@code null} only for callers that decide without a policy
     * @param llmCostUsd the USD cost of the LLM fallback when the decision was routed to it
     *              (story 05.02), or {@code null} on any decision that did not call the LLM
     */
    public Classification save(UUID emailId, DecisionOutcome outcome, FusedScore fused,
            String policyVersion, BigDecimal llmCostUsd) {
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
                ps.setNull(10, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(7, scores.spamScore());
                ps.setDouble(8, scores.phishingScore());
                ps.setString(9, scores.modelVersion());
                ps.setDouble(10, scores.calibratedConfidence());
            }
            // The posterior is present only once a model-route score has been fused with
            // reputation; otherwise (hard rule, or uncalibrated model) the row stores NULLs.
            if (fused == null) {
                ps.setNull(11, java.sql.Types.DOUBLE);
                ps.setNull(12, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(11, fused.posterior());
                ps.setDouble(12, fused.uncertaintyBand());
            }
            if (policyVersion == null) {
                ps.setNull(13, java.sql.Types.VARCHAR);
            } else {
                ps.setString(13, policyVersion);
            }
            // Cost is present only when the decision was routed to the LLM (story 05.02); every
            // other route stores NULL, distinguishing "no LLM call" from a real zero-cost call.
            if (llmCostUsd == null) {
                ps.setNull(14, java.sql.Types.NUMERIC);
            } else {
                ps.setBigDecimal(14, llmCostUsd);
            }
            return ps;
        }, rs -> {
            rs.next();
            return rs.getObject("created_at", OffsetDateTime.class);
        });

        return new Classification(
                id, emailId, outcome.decision(), outcome.reasonCodes(),
                outcome.route(), outcome.latencyMs(), scores, fused, policyVersion, llmCostUsd,
                createdAt.toInstant());
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
                fusedScore(rs),
                rs.getString("policy_version"),
                rs.getBigDecimal("llm_cost_usd"),
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
                rs.getDouble("spam_score"), rs.getDouble("phishing_score"), modelVersion,
                rs.getDouble("calibrated_confidence"));
    }

    /**
     * Reconstructs the fused posterior, or {@code null} when the row was never fused.
     * {@code posterior} is the discriminator: it is non-null exactly when reputation was
     * fused with a calibrated model score. The {@code posteriorLogit} is re-derived from
     * the stored posterior rather than persisted, since the two are inverses.
     *
     * <p>{@code senderUncertainty} is reconstructed as {@code 0}: it is a decide-time routing
     * input (story 05.01), not persisted, and routing never re-runs against a stored decision —
     * the read path serves the recorded verdict, so the value is not needed here.
     */
    private static FusedScore fusedScore(java.sql.ResultSet rs) throws java.sql.SQLException {
        double posterior = rs.getDouble("posterior");
        if (rs.wasNull()) {
            return null;
        }
        return new FusedScore(
                posterior, LogOddsFusion.logit(posterior), rs.getDouble("uncertainty_band"), 0.0);
    }

    private static List<ReasonCode> reasonCodes(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        String[] names = (String[]) array.getArray();
        return List.of(names).stream().map(ReasonCode::valueOf).toList();
    }
}
