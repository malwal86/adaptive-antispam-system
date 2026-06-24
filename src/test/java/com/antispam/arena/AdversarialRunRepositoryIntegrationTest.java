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
 * with its terminal status, achieved bypass rate, and consumed bounds. Skips without Docker.
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
    void unknown_run_id_reads_back_empty() {
        assertThat(runs.findById(UUID.randomUUID())).isEmpty();
    }
}
