package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the {@code adversarial_runs} lifecycle against the real database (story 08.02): a run is
 * opened in {@code running} with its config and captured fixed defender (AC 1, AC 4), then finalized
 * with its terminal status, achieved bypass rate, and consumed bounds. The baseline stamp and the
 * cross-run trend query are story 08.04. Skips without Docker.
 */
class AdversarialRunRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AdversarialRunRepository runs;

    @Test
    void start_records_the_config_and_the_captured_fixed_defender() {
        AdversarialRun run = runs.start("gpt-4o", "model-7", "pol-active",
                0.4, 3, new BigDecimal("1.00"));

        assertThat(run.attackerModel()).isEqualTo("gpt-4o");
        assertThat(run.defenderModel()).isEqualTo("model-7");
        assertThat(run.defenderPolicyVersion()).isEqualTo("pol-active");
        assertThat(run.targetBypassRate()).isEqualTo(0.4);
        assertThat(run.generationCap()).isEqualTo(3);
        assertThat(run.budgetUsd()).isEqualByComparingTo("1.00");
        // Opens running: no result yet, nothing consumed.
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.actualBypassRate()).isNull();
        assertThat(run.precisionFpRate()).isNull();
        assertThat(run.generationsRun()).isZero();
        assertThat(run.spentUsd()).isEqualByComparingTo("0");
        assertThat(run.completedAt()).isNull();
        assertThat(runs.findById(run.id())).contains(run);
    }

    @Test
    void complete_finalizes_the_run_with_its_result_and_consumed_bounds() {
        AdversarialRun started = runs.start("gpt-4o", "model-7", "pol-active",
                0.4, 5, new BigDecimal("2.00"));

        AdversarialRun done = runs.complete(started.id(), 0.6, 0.2, new BigDecimal("0.18"), 4,
                RunStatus.BUDGET_EXHAUSTED);

        assertThat(done.status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
        assertThat(done.actualBypassRate()).isEqualTo(0.6);
        // Two-track: recall and precision recorded separately (story 08.02b).
        assertThat(done.precisionFpRate()).isEqualTo(0.2);
        assertThat(done.generationsRun()).isEqualTo(4);
        assertThat(done.spentUsd()).isEqualByComparingTo("0.18");
        assertThat(done.completedAt()).isNotNull();
        assertThat(runs.findById(started.id())).contains(done);
    }

    @Test
    void record_baseline_stamps_the_comparison_on_a_finalized_run() {
        AdversarialRun started = runs.start("gpt-4o", "model-7", "pol-active",
                0.4, 3, new BigDecimal("1.00"));
        runs.complete(started.id(), 0.3, null, new BigDecimal("0.05"), 2, RunStatus.COMPLETED);

        AdversarialRun measured = runs.recordBaseline(started.id(), "pol-genesis", 0.7);

        // The baseline comparison is recorded without disturbing the run's own result (story 08.04).
        assertThat(measured.baselinePolicyVersion()).isEqualTo("pol-genesis");
        assertThat(measured.baselineBypassRate()).isEqualTo(0.7);
        assertThat(measured.actualBypassRate()).isEqualTo(0.3);
        assertThat(measured.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runs.findById(started.id())).contains(measured);
    }

    @Test
    void find_recent_terminal_returns_completed_runs_oldest_first_and_excludes_failed() {
        AdversarialRun older = runs.start("gpt-4o", "model-7", "pol-active", 0.4, 3, new BigDecimal("1.00"));
        runs.complete(older.id(), 0.6, null, new BigDecimal("0.05"), 2, RunStatus.COMPLETED);
        AdversarialRun failed = runs.start("gpt-4o", "model-7", "pol-active", 0.4, 3, new BigDecimal("1.00"));
        runs.complete(failed.id(), null, null, new BigDecimal("0.01"), 0, RunStatus.FAILED);
        AdversarialRun newer = runs.start("gpt-4o", "model-7", "pol-active", 0.4, 3, new BigDecimal("1.00"));
        runs.complete(newer.id(), 0.3, null, new BigDecimal("0.05"), 2, RunStatus.COMPLETED);

        var recent = runs.findRecentTerminal(50);

        var ids = recent.stream().map(AdversarialRun::id).toList();
        // Both terminal runs are present; the failed one (no meaningful rate) is excluded.
        assertThat(ids).contains(older.id(), newer.id()).doesNotContain(failed.id());
        // Oldest-first, so the older run precedes the newer one — the trend reads left to right in time.
        assertThat(ids.indexOf(older.id())).isLessThan(ids.indexOf(newer.id()));
    }

    @Test
    void unknown_run_id_reads_back_empty() {
        assertThat(runs.findById(UUID.randomUUID())).isEmpty();
    }
}
