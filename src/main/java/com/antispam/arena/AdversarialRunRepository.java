package com.antispam.arena;

import com.antispam.common.JdbcTimestamps;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code adversarial_runs} table (story 08.02). A run is written in up to three steps:
 * {@link #start} when the loop begins (status {@code running}, the config and the captured fixed
 * defender recorded), {@link #complete} when it terminates (the terminal status, the achieved bypass
 * rate, and the consumed bounds), and {@link #recordBaseline} in the post-loop measurement step (story
 * 08.04) which stamps the baseline comparison. There is no other mutation — the per-generation detail
 * lives in the child {@code adversarial_emails} rows, not here.
 */
@Repository
public class AdversarialRunRepository {

    private static final String COLUMNS = """
            id, attacker_model, defender_model, defender_policy_version,
            target_bypass_rate, actual_bypass_rate, precision_fp_rate,
            baseline_policy_version, baseline_bypass_rate, generation_cap, budget_usd,
            spent_usd, generations_run, status, created_at, completed_at
            """;

    private static final String INSERT_SQL = """
            insert into adversarial_runs (
                id, attacker_model, defender_model, defender_policy_version,
                target_bypass_rate, generation_cap, budget_usd, status)
            values (?, ?, ?, ?, ?, ?, ?, 'running')
            returning
            """ + COLUMNS;

    private static final String COMPLETE_SQL = """
            update adversarial_runs
               set actual_bypass_rate = ?, precision_fp_rate = ?, spent_usd = ?, generations_run = ?,
                   status = ?, completed_at = now()
             where id = ?
            returning
            """ + COLUMNS;

    private static final String RECORD_BASELINE_SQL = """
            update adversarial_runs
               set baseline_policy_version = ?, baseline_bypass_rate = ?
             where id = ?
            returning
            """ + COLUMNS;

    private static final String SELECT_BY_ID_SQL =
            "select " + COLUMNS + " from adversarial_runs where id = ?";

    // The most recent terminal runs, returned oldest-first so a reader sees the bypass-rate trend in
    // chronological order (story 08.04). Only runs that produced a rate are included — a failed run's
    // partial state would be misleading on a trend line.
    private static final String SELECT_RECENT_TERMINAL_SQL = "select " + COLUMNS + """
             from (
                select
            """ + COLUMNS + """
                  from adversarial_runs
                 where status in ('completed', 'budget_exhausted')
                 order by created_at desc, id desc
                 limit ?
            ) recent
            order by created_at, id
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
     * The two rates are reported separately (story 08.02b, AC 3); each is null when its track did not run.
     *
     * @param actualBypassRate Track A recall: abuse variants delivered / abuse variants scored, in
     *                         [0,1]; null if no Track A ran
     * @param precisionFpRate  Track B precision: legit variants wrongly blocked / legit variants scored,
     *                         in [0,1]; null if no Track B ran
     */
    public AdversarialRun complete(UUID runId, Double actualBypassRate, Double precisionFpRate,
            BigDecimal spentUsd, int generationsRun, RunStatus status) {
        return jdbc.queryForObject(COMPLETE_SQL, RUN_MAPPER,
                actualBypassRate, precisionFpRate, spentUsd, generationsRun, status.dbValue(), runId);
    }

    /**
     * Stamps the baseline comparison on a terminated run (story 08.04): the fixed baseline defender the
     * run's variants were also scored against and the bypass rate they achieved under it. Called once,
     * in the post-loop measurement step, after {@link #complete}.
     *
     * @param baselinePolicyVersion the fixed reference defender, or null if none was resolvable
     * @param baselineBypassRate    the Track A bypass rate under that baseline in [0,1], or null if no
     *                              baseline ran or the run had no Track A
     */
    public AdversarialRun recordBaseline(UUID runId, String baselinePolicyVersion,
            Double baselineBypassRate) {
        return jdbc.queryForObject(RECORD_BASELINE_SQL, RUN_MAPPER,
                baselinePolicyVersion, baselineBypassRate, runId);
    }

    public Optional<AdversarialRun> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_ID_SQL, RUN_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * The {@code limit} most recent terminal runs, oldest-first — the cross-run bypass-rate trend
     * (story 08.04, AC 4). Failed runs are excluded; their partial state has no meaningful rate.
     */
    public List<AdversarialRun> findRecentTerminal(int limit) {
        return jdbc.query(SELECT_RECENT_TERMINAL_SQL, RUN_MAPPER, limit);
    }

    private static final RowMapper<AdversarialRun> RUN_MAPPER = (rs, rowNum) -> {
        Double actual = rs.getObject("actual_bypass_rate", Double.class);
        Double precisionFp = rs.getObject("precision_fp_rate", Double.class);
        Double baselineBypass = rs.getObject("baseline_bypass_rate", Double.class);
        return new AdversarialRun(
                rs.getObject("id", UUID.class),
                rs.getString("attacker_model"),
                rs.getString("defender_model"),
                rs.getString("defender_policy_version"),
                rs.getDouble("target_bypass_rate"),
                actual,
                precisionFp,
                rs.getString("baseline_policy_version"),
                baselineBypass,
                rs.getInt("generation_cap"),
                rs.getBigDecimal("budget_usd"),
                rs.getBigDecimal("spent_usd"),
                rs.getInt("generations_run"),
                RunStatus.fromDbValue(rs.getString("status")),
                JdbcTimestamps.instantOrNull(rs, "created_at"),
                JdbcTimestamps.instantOrNull(rs, "completed_at"));
    };
}
