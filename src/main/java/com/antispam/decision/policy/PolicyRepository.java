package com.antispam.decision.policy;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access to the {@code policies} table — the versioned decision regimes (story 04.05).
 * Reads are dominated by {@link #findActive()}, the single enforcing regime the decision
 * path looks up per mail; {@link #activate(String)} flips which version that is, the same
 * mechanism the retrain loop (Epic 10) uses to promote a regime and shadow/replay (Epic 09)
 * uses to compare them.
 *
 * <p>At most one row is active at a time, enforced by a partial unique index in the schema.
 * {@link #activate} therefore clears the current active flag before setting the new one,
 * in one transaction, so the switch is atomic and never transiently violates the index or
 * leaves the system with no active policy.
 */
@Repository
public class PolicyRepository {

    private static final String INSERT_SQL = """
            insert into policies (
                version, active, warn_threshold, quarantine_threshold, block_threshold,
                llm_threshold, routing_band_width, burst_threshold, model_version)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning created_at
            """;

    private static final String SELECT_ACTIVE_SQL = """
            select version, active, warn_threshold, quarantine_threshold, block_threshold,
                   llm_threshold, routing_band_width, burst_threshold, model_version, created_at
            from policies
            where active
            """;

    private static final String SELECT_BY_VERSION_SQL = """
            select version, active, warn_threshold, quarantine_threshold, block_threshold,
                   llm_threshold, routing_band_width, burst_threshold, model_version, created_at
            from policies
            where version = ?
            """;

    private static final String SELECT_SHADOW_SQL = """
            select version, active, warn_threshold, quarantine_threshold, block_threshold,
                   llm_threshold, routing_band_width, burst_threshold, model_version, created_at
            from policies
            where shadow
            """;

    private static final String SELECT_OLDEST_SQL = """
            select version, active, warn_threshold, quarantine_threshold, block_threshold,
                   llm_threshold, routing_band_width, burst_threshold, model_version, created_at
            from policies
            order by created_at, version
            limit 1
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The one enforcing regime, or empty if none is active (a misconfiguration). */
    public Optional<Policy> findActive() {
        return jdbc.query(SELECT_ACTIVE_SQL, POLICY_MAPPER).stream().findFirst();
    }

    public Optional<Policy> findByVersion(String version) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_VERSION_SQL, POLICY_MAPPER, version));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * The genesis policy: the earliest-created regime, the original calibrated defender before any
     * retrain promoted a newer one (Epic 10). The adversarial arena uses it as the fixed baseline its
     * variants are also scored against (story 08.04), so the "danger missed by baseline" comparison and
     * the cross-run bypass-rate trend stay anchored to one stable reference. Empty only when no policy
     * exists at all.
     */
    public Optional<Policy> findOldest() {
        return jdbc.query(SELECT_OLDEST_SQL, POLICY_MAPPER).stream().findFirst();
    }

    /**
     * The policy designated as the shadow (logged-only) regime for live shadow scoring (story
     * 09.02), or empty when none is configured — in which case shadow scoring is a no-op and the
     * live path is wholly unaffected. At most one row is ever flagged shadow (partial unique index).
     */
    public Optional<Policy> findShadow() {
        return jdbc.query(SELECT_SHADOW_SQL, POLICY_MAPPER).stream().findFirst();
    }

    /**
     * Designates {@code version} as the sole shadow policy: clears the current shadow flag, then
     * sets it on {@code version}, atomically — mirroring {@link #activate}. The shadow may equal the
     * active policy (a degenerate no-diff configuration), so this does not check against active.
     *
     * @throws IllegalArgumentException if no policy has that version
     */
    @Transactional
    public void markShadow(String version) {
        jdbc.update("update policies set shadow = false where shadow");
        int updated = jdbc.update("update policies set shadow = true where version = ?", version);
        if (updated == 0) {
            throw new IllegalArgumentException("no policy to mark shadow with version " + version);
        }
    }

    /** Clears the shadow designation, turning live shadow scoring off. A no-op when none is set. */
    @Transactional
    public void clearShadow() {
        jdbc.update("update policies set shadow = false where shadow");
    }

    /**
     * Persists a new policy and returns it with its assigned {@code created_at}. The
     * caller decides whether it is active; saving a second active policy directly would
     * violate the one-active index, so use {@link #activate} to switch.
     */
    public Policy save(Policy policy) {
        OffsetDateTime createdAt = jdbc.queryForObject(INSERT_SQL, (rs, rowNum) ->
                        rs.getObject("created_at", OffsetDateTime.class),
                policy.version(), policy.active(), policy.warnThreshold(), policy.quarantineThreshold(),
                policy.blockThreshold(), policy.llmThreshold(), policy.routingBandWidth(),
                policy.burstThreshold(), policy.modelVersion());
        return new Policy(policy.version(), policy.active(), policy.warnThreshold(),
                policy.quarantineThreshold(), policy.blockThreshold(), policy.llmThreshold(),
                policy.routingBandWidth(), policy.burstThreshold(), policy.modelVersion(),
                createdAt.toInstant());
    }

    /**
     * Makes {@code version} the sole active policy: clears the current active flag, then
     * sets it on {@code version}, atomically.
     *
     * @throws IllegalArgumentException if no policy has that version
     */
    @Transactional
    public void activate(String version) {
        // Clearing matches 0 rows on the very first activation — that is fine.
        jdbc.update("update policies set active = false where active");
        int updated = jdbc.update("update policies set active = true where version = ?", version);
        if (updated == 0) {
            throw new IllegalArgumentException("no policy to activate with version " + version);
        }
    }

    private static final RowMapper<Policy> POLICY_MAPPER = (rs, rowNum) -> {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new Policy(
                rs.getString("version"),
                rs.getBoolean("active"),
                rs.getDouble("warn_threshold"),
                rs.getDouble("quarantine_threshold"),
                rs.getDouble("block_threshold"),
                rs.getDouble("llm_threshold"),
                rs.getDouble("routing_band_width"),
                rs.getInt("burst_threshold"),
                rs.getString("model_version"),
                createdAt == null ? null : createdAt.toInstant());
    };
}
