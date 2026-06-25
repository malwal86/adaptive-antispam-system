package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof of the thunderclap runner against the full stack (story 12.05): a single
 * {@code start} drives the <em>real</em> decision pipeline, so every scripted email is ingested with
 * its beat provenance, decided, published onto the live stream, and a shadow policy is designated for
 * the shadow-diff beat. The auth-gating beats themselves (spoof gets nothing, misconfigured-legit
 * still accrues) are proven against real reputation in {@code ReputationAuthGatingIntegrationTest};
 * here we prove the orchestration that delivers those emails into the pipeline as one control action.
 *
 * <p>The dispatcher is synchronous and the step delay zero (see the nested config) so the
 * asynchronously-paced run completes inline and its effects are observable deterministically. The
 * shadow flag is a shared global, so it is cleared after each test per the shared-DB convention.
 */
class ThunderclapScenarioIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Runs the injection loop inline (no background thread) and removes pacing, so the run is synchronous. */
    @TestConfiguration
    static class SynchronousRunnerConfig {
        @Bean
        @Primary
        ScenarioDispatcher inlineDispatcher() {
            return Runnable::run;
        }

        @Bean
        @Primary
        ScenarioProperties immediateScenarioProperties() {
            return new ScenarioProperties(Duration.ZERO, 1234L);
        }
    }

    @Autowired
    private ScenarioService scenarios;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearShadow() {
        policies.clearShadow();
    }

    @Test
    void a_single_start_drives_the_live_pipeline_ingests_every_beat_and_designates_a_shadow() {
        int warmUpBefore = decided(Beat.WARMUP);
        int attackBefore = decided(Beat.ATTACK);
        int spoofBefore = decided(Beat.SPOOF);
        int legitBefore = decided(Beat.MISCONFIGURED_LEGIT);

        ScenarioRun run = scenarios.start(ThunderclapScript.NAME, 2026L);

        ThunderclapScript.Plan plan = ThunderclapScript.Plan.DEFAULT;
        assertThat(run.steps())
                .isEqualTo(plan.warmUps() + plan.attackVariants() + 1 + plan.misconfiguredLegit());

        // One control action drove the real pipeline: every scripted email was ingested with its beat
        // provenance and decided (a classification row exists), in the planned per-beat counts — the
        // runner is wired AnalyzeService → DecisionService → persisted decision, not a mock. (That each
        // decision also publishes onto the live SSE stream is pinned by DecisionStreamWiringIntegrationTest.)
        assertThat(decided(Beat.WARMUP) - warmUpBefore).isEqualTo(plan.warmUps());
        assertThat(decided(Beat.ATTACK) - attackBefore).isEqualTo(plan.attackVariants());
        assertThat(decided(Beat.SPOOF) - spoofBefore).isEqualTo(1);
        assertThat(decided(Beat.MISCONFIGURED_LEGIT) - legitBefore).isEqualTo(plan.misconfiguredLegit());

        // The shadow-diff beat is live: a stricter shadow policy was designated for the run.
        Policy shadow = policies.findShadow().orElseThrow();
        assertThat(shadow.version()).startsWith("thunderclap-shadow-");
        assertThat(run.shadowPolicyVersion()).isEqualTo(shadow.version());
    }

    /** How many emails of this beat have been decided (a classification joined to its email row). */
    private int decided(Beat beat) {
        Integer count = jdbc.queryForObject(
                "select count(*) from classifications c join emails e on c.email_id = e.id "
                        + "where e.ingest_source = ?",
                Integer.class, beat.source());
        return count == null ? 0 : count;
    }
}
