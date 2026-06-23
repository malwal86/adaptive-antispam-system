package com.antispam.experiment.replay;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.decision.routing.RoutingReason;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code replay_decisions} table (story 09.01) — the experiment-scoped sink for
 * replay verdicts, kept strictly separate from {@code classifications} so a replay output is never
 * mistaken for an enforced decision.
 *
 * <p>{@link #save} is idempotent on {@code (run_id, email_id)}: the scorer is deterministic, so a
 * Kafka redelivery of the same replay message must not mint a second row. The insert is
 * on-conflict-do-nothing and reports whether it actually wrote, so the consumer can treat a
 * duplicate as a no-op.
 */
@Repository
public class ReplayDecisionRepository {

    private static final String INSERT_SQL = """
            insert into replay_decisions (
                id, run_id, policy_version, email_id, decision, route_used,
                reason_codes, routing_reasons, posterior)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (run_id, email_id) do nothing
            """;

    private static final String SELECT_BY_RUN_SQL = """
            select id, run_id, policy_version, email_id, decision, route_used,
                   reason_codes, routing_reasons, posterior, created_at
            from replay_decisions
            where run_id = ?
            order by created_at, email_id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public ReplayDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists {@code scored} as the verdict for {@code emailId} in replay {@code runId}, or does
     * nothing if a row for that (run, email) already exists.
     *
     * @return {@code true} if a row was written, {@code false} if it was a duplicate (already scored)
     */
    public boolean save(UUID runId, UUID emailId, ScoredDecision scored) {
        String[] reasonCodes = scored.reasonCodes().stream().map(Enum::name).toArray(String[]::new);
        String[] routingReasons = scored.routingReasons().stream().map(Enum::name).toArray(String[]::new);
        int inserted = jdbc.update(connection -> {
            var ps = connection.prepareStatement(INSERT_SQL);
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, runId);
            ps.setString(3, scored.policyVersion());
            ps.setObject(4, emailId);
            ps.setString(5, scored.decision().name());
            ps.setString(6, scored.route().name());
            ps.setArray(7, connection.createArrayOf("text", reasonCodes));
            ps.setArray(8, connection.createArrayOf("text", routingReasons));
            if (scored.posterior() == null) {
                ps.setNull(9, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(9, scored.posterior());
            }
            return ps;
        });
        return inserted > 0;
    }

    /** Every decision of one replay, oldest first — for inspection and the 09.04 A/B harness. */
    public List<ReplayDecision> findByRunId(UUID runId) {
        return jdbc.query(SELECT_BY_RUN_SQL, REPLAY_DECISION_MAPPER, runId);
    }

    private static final RowMapper<ReplayDecision> REPLAY_DECISION_MAPPER = (rs, rowNum) -> {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        double posterior = rs.getDouble("posterior");
        Double boxedPosterior = rs.wasNull() ? null : posterior;
        ScoredDecision scored = new ScoredDecision(
                Decision.valueOf(rs.getString("decision")),
                names(rs.getArray("reason_codes"), ReasonCode::valueOf),
                RouteUsed.valueOf(rs.getString("route_used")),
                names(rs.getArray("routing_reasons"), RoutingReason::valueOf),
                rs.getString("policy_version"),
                boxedPosterior);
        return new ReplayDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("email_id", UUID.class),
                scored,
                createdAt == null ? null : createdAt.toInstant());
    };

    private static <E> List<E> names(Array array, java.util.function.Function<String, E> parse)
            throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        String[] names = (String[]) array.getArray();
        return List.of(names).stream().map(parse).toList();
    }
}
