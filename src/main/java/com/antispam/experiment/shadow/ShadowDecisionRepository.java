package com.antispam.experiment.shadow;

import com.antispam.common.JdbcTimestamps;
import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.shadow.ShadowDiff.Agreement;
import com.antispam.experiment.shadow.ShadowDiff.Direction;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code shadow_decisions} table (story 09.02) — append-only, like {@code classifications}:
 * each live decision produces a new row when a shadow policy is configured. Reads serve the two
 * needs the PRD names: per-email ("what would the shadow have done to this mail") and the
 * promotion-evidence aggregation ({@link #agreementStats} — agreement and direction rates for an
 * active-vs-shadow pairing, which Epic 10's gate weighs).
 */
@Repository
public class ShadowDecisionRepository {

    private static final String INSERT_SQL = """
            insert into shadow_decisions (
                id, email_id, active_policy_version, shadow_policy_version,
                active_decision, shadow_decision, active_route, shadow_route,
                active_posterior, shadow_posterior, agreement, direction)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning created_at
            """;

    private static final String SELECT_BY_EMAIL_SQL = """
            select id, email_id, active_policy_version, shadow_policy_version,
                   active_decision, shadow_decision, active_route, shadow_route,
                   active_posterior, shadow_posterior, agreement, direction, created_at
            from shadow_decisions
            where email_id = ?
            order by created_at
            """;

    private static final String AGREEMENT_STATS_SQL = """
            select count(*) as total,
                   count(*) filter (where agreement = 'AGREE') as agree,
                   count(*) filter (where agreement = 'DISAGREE') as disagree,
                   count(*) filter (where direction = 'SHADOW_MORE_SEVERE') as shadow_more_severe,
                   count(*) filter (where direction = 'SHADOW_LESS_SEVERE') as shadow_less_severe
            from shadow_decisions
            where active_policy_version = ? and shadow_policy_version = ?
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public ShadowDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the active and shadow verdicts for {@code emailId} and their diff, returning the
     * stored {@link ShadowDecision} with its generated id and timestamp.
     */
    public ShadowDecision save(UUID emailId, ScoredDecision active, ScoredDecision shadow, ShadowDiff diff) {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = jdbc.query(connection -> {
            var ps = connection.prepareStatement(INSERT_SQL);
            ps.setObject(1, id);
            ps.setObject(2, emailId);
            ps.setString(3, active.policyVersion());
            ps.setString(4, shadow.policyVersion());
            ps.setString(5, active.decision().name());
            ps.setString(6, shadow.decision().name());
            ps.setString(7, active.route().name());
            ps.setString(8, shadow.route().name());
            setNullableDouble(ps, 9, active.posterior());
            setNullableDouble(ps, 10, shadow.posterior());
            ps.setString(11, diff.agreement().name());
            ps.setString(12, diff.direction().name());
            return ps;
        }, rs -> {
            rs.next();
            return rs.getObject("created_at", OffsetDateTime.class);
        });
        return new ShadowDecision(id, emailId, active, shadow, diff, createdAt.toInstant());
    }

    /** Every shadow decision recorded for {@code emailId}, oldest first. */
    public List<ShadowDecision> findByEmailId(UUID emailId) {
        return jdbc.query(SELECT_BY_EMAIL_SQL, SHADOW_DECISION_MAPPER, emailId);
    }

    /**
     * Agreement and direction counts for a given active-vs-shadow pairing — the promotion evidence
     * Epic 10's gate consumes. A pairing with no rows yet returns an all-zero summary.
     */
    public AgreementStats agreementStats(String activePolicyVersion, String shadowPolicyVersion) {
        return jdbc.queryForObject(AGREEMENT_STATS_SQL, (rs, rowNum) -> new AgreementStats(
                activePolicyVersion, shadowPolicyVersion,
                rs.getLong("total"), rs.getLong("agree"), rs.getLong("disagree"),
                rs.getLong("shadow_more_severe"), rs.getLong("shadow_less_severe")),
                activePolicyVersion, shadowPolicyVersion);
    }

    /**
     * Aggregated shadow evidence for an active-vs-shadow pairing.
     *
     * @param activePolicyVersion the enforced regime
     * @param shadowPolicyVersion the candidate regime
     * @param total               decisions scored under both
     * @param agree               how many landed on the same tier
     * @param disagree            how many differed
     * @param shadowMoreSevere    of the disagreements, how many the shadow would escalate
     * @param shadowLessSevere    of the disagreements, how many the shadow would soften
     */
    public record AgreementStats(
            String activePolicyVersion, String shadowPolicyVersion, long total, long agree,
            long disagree, long shadowMoreSevere, long shadowLessSevere) {
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static final RowMapper<ShadowDecision> SHADOW_DECISION_MAPPER = (rs, rowNum) -> {
        ScoredDecision active = side(rs, "active");
        ScoredDecision shadow = side(rs, "shadow");
        ShadowDiff diff = new ShadowDiff(
                Agreement.valueOf(rs.getString("agreement")),
                Direction.valueOf(rs.getString("direction")));
        return new ShadowDecision(
                rs.getObject("id", UUID.class), rs.getObject("email_id", UUID.class),
                active, shadow, diff, JdbcTimestamps.instantOrNull(rs, "created_at"));
    };

    /**
     * Reconstructs one side's {@link ScoredDecision} from the row. The reason codes and routing
     * reasons are not persisted on a shadow row (the diff, not the justification, is what the gate
     * weighs), so they are read back empty — the recorded fields are the verdict, route, posterior,
     * and policy version.
     */
    private static ScoredDecision side(java.sql.ResultSet rs, String prefix) throws java.sql.SQLException {
        double posterior = rs.getDouble(prefix + "_posterior");
        Double boxedPosterior = rs.wasNull() ? null : posterior;
        return new ScoredDecision(
                Decision.valueOf(rs.getString(prefix + "_decision")),
                List.of(),
                RouteUsed.valueOf(rs.getString(prefix + "_route")),
                List.of(),
                rs.getString(prefix + "_policy_version"),
                boxedPosterior);
    }
}
