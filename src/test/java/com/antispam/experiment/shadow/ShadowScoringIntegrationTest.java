package com.antispam.experiment.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.DecisionService;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.experiment.shadow.ShadowDecisionRepository.AgreementStats;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves live shadow scoring end-to-end (story 09.02): with a shadow policy configured, every live
 * decision also produces a {@code shadow_decisions} row recording the active and shadow verdicts and
 * their diff — while enforcing nothing from the shadow path (exactly one live classification per
 * email) — and the agreement stats aggregate those rows as promotion evidence. Postgres only; the
 * decision is driven directly via {@link DecisionService}, so no broker is needed.
 *
 * <p>The shadow executor is overridden to run inline so the recorded row is durable before the
 * assertions, the same technique the quarantine-pending integration test uses for its resolution
 * executor. Skips without Docker; runs in full in CI.
 */
class ShadowScoringIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SHADOW_VERSION = "shadow-it-strict";

    @TestConfiguration
    static class SynchronousShadowConfig {
        @Bean
        Executor shadowScoringExecutor() {
            return Runnable::run;
        }
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private ShadowDecisionRepository shadowDecisions;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearShadowDesignation() {
        // Global state: leave no shadow configured so other decision tests are unaffected.
        policies.clearShadow();
    }

    @Test
    void records_a_shadow_decision_without_enforcing_anything_from_the_shadow_path() {
        String activeVersion = policies.findActive().orElseThrow().version();
        ensureShadowPolicyExists();
        policies.markShadow(SHADOW_VERSION);

        Email email = ingest("From: stranger@elsewhere.test\nSubject: hi\n\nplease review this\n");
        decisionService.decide(email);

        List<ShadowDecision> rows = shadowDecisions.findByEmailId(email.id());
        assertThat(rows).hasSize(1);
        ShadowDecision row = rows.get(0);
        assertThat(row.active().policyVersion()).isEqualTo(activeVersion);
        assertThat(row.shadow().policyVersion()).isEqualTo(SHADOW_VERSION);
        assertThat(row.diff().agreement()).isNotNull();
        assertThat(row.diff().direction()).isNotNull();

        // Isolation: the shadow path enforced nothing — exactly one live classification for the email.
        assertThat(classificationCount(email.id())).isEqualTo(1);
    }

    @Test
    void records_no_shadow_decision_when_no_shadow_policy_is_configured() {
        policies.clearShadow();

        Email email = ingest("From: someone@elsewhere.test\nSubject: hi\n\njust checking in\n");
        decisionService.decide(email);

        assertThat(shadowDecisions.findByEmailId(email.id())).isEmpty();
        // The live decision still happened — shadow being off does not affect the enforced path.
        assertThat(classificationCount(email.id())).isEqualTo(1);
    }

    @Test
    void aggregates_recorded_diffs_into_agreement_stats() {
        String activeVersion = policies.findActive().orElseThrow().version();
        ensureShadowPolicyExists();
        policies.markShadow(SHADOW_VERSION);

        decisionService.decide(ingest("From: a@elsewhere.test\nSubject: x\n\nhello there\n"));
        decisionService.decide(ingest("From: b@elsewhere.test\nSubject: y\n\ngreetings friend\n"));

        AgreementStats stats = shadowDecisions.agreementStats(activeVersion, SHADOW_VERSION);
        assertThat(stats.total()).isGreaterThanOrEqualTo(2);
        assertThat(stats.agree() + stats.disagree()).isEqualTo(stats.total());
    }

    /** A strict shadow regime (low thresholds) distinct from any active policy, created once. */
    private void ensureShadowPolicyExists() {
        if (policies.findByVersion(SHADOW_VERSION).isEmpty()) {
            policies.save(new Policy(
                    SHADOW_VERSION, false, 0.05, 0.10, 0.20, 0.40, 0.05, 20, "bootstrap-v1", null));
        }
    }

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    private long classificationCount(UUID emailId) {
        Long count = jdbc.queryForObject(
                "select count(*) from classifications where email_id = ?", Long.class, emailId);
        return count == null ? 0 : count;
    }
}
