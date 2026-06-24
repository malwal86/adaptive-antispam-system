package com.antispam.arena;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code adversarial_runs} table (story 08.02). A run is written exactly twice: once
 * at {@link #start} when the loop begins (status {@code running}, the config and the captured fixed
 * defender recorded), and once at {@link #complete} when it terminates (the terminal status, the
 * achieved bypass rate, and the consumed bounds). There is no other mutation — the per-generation
 * detail lives in the child {@code adversarial_emails} rows, not here.
 */
@Repository
public class AdversarialRunRepository {

    private static final String INSERT_SQL = """
            insert into adversarial_runs (
                id, attacker_model, defender_model, defender_policy_version,
                target_bypass_rate, generation_cap, budget_usd, status)
            values (?, ?, ?, ?, ?, ?, ?, 'running')
            returning id, attacker_model, defender_model, defender_policy_version,
                      target_bypass_rate, actual_bypass_rate, generation_cap, budget_usd,
                      spent_usd, generations_run, status, created_at, completed_at
            """;

    private static final String COMPLETE_SQL = """
            update adversarial_runs
               set actual_bypass_rate = ?, spent_usd = ?, generations_run = ?,
                   status = ?, completed_at = now()
             where id = ?
            returning id, attacker_model, defender_model, defender_policy_version,
                      target_bypass_rate, actual_bypass_rate, generation_cap, budget_usd,
                      spent_usd, generations_run, status, created_at, completed_at
            """;

    private static final String SELECT_BY_ID_SQL = """
            select id, attacker_model, defender_model, defender_policy_version,
                   target_bypass_rate, actual_bypass_rate, generation_cap, budget_usd,
                   spent_usd, generations_run, status, created_at, completed_at
            from adversarial_runs where id = ?
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public AdversarialRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Opens a new run in {@code running} state, recording the config and the defender captured fixed
     * for its duration. The returned run carries the generated id every variant of this campaign
     * will reference.
     *
     * @param defenderPolicyVersion the active policy captured once at start; every generation scores
     *                              under exactly this version (AC 4)
     */
    public AdversarialRun start(String attackerModel, String defenderModel, String defenderPolicyVersion,
            double targetBypassRate, int generationCap, BigDecimal budgetUsd) {
        return jdbc.queryForObject(INSERT_SQL, RUN_MAPPER,
                UUID.randomUUID(), attackerModel, defenderModel, defenderPolicyVersion,
                targetBypassRate, generationCap, budgetUsd);
    }

    /**
     * Finalizes a run with its terminal outcome. Called for every run that left {@code running},
     * including a budget-exhausted one (partial results) and a failed one — so no run is left dangling.
     *
     * @param actualBypassRate bypassing variants / variants scored across the run, in [0,1]
     */
    public AdversarialRun complete(UUID runId, double actualBypassRate, BigDecimal spentUsd,
            int generationsRun, RunStatus status) {
        return jdbc.queryForObject(COMPLETE_SQL, RUN_MAPPER,
                actualBypassRate, spentUsd, generationsRun, status.dbValue(), runId);
    }

    public Optional<AdversarialRun> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_ID_SQL, RUN_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final RowMapper<AdversarialRun> RUN_MAPPER = (rs, rowNum) -> {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
        Double actual = rs.getObject("actual_bypass_rate", Double.class);
        return new AdversarialRun(
                rs.getObject("id", UUID.class),
                rs.getString("attacker_model"),
                rs.getString("defender_model"),
                rs.getString("defender_policy_version"),
                rs.getDouble("target_bypass_rate"),
                actual,
                rs.getInt("generation_cap"),
                rs.getBigDecimal("budget_usd"),
                rs.getBigDecimal("spent_usd"),
                rs.getInt("generations_run"),
                RunStatus.fromDbValue(rs.getString("status")),
                createdAt == null ? null : createdAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    };
}
