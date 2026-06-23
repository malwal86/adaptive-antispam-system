package com.antispam.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.event.ReplayEmailEvent;
import com.antispam.experiment.replay.ReplayScoringService;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationEvent;
import com.antispam.reputation.ReputationRepository;
import com.antispam.reputation.ReputationSignal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves side-effect isolation end-to-end (story 09.03, PRD §Subsystem 8): an experiment path scores
 * against live state but mutates none of it, and a deliberate live-state write from a read-only
 * experiment context is blocked at the database boundary, not merely discouraged. Postgres only;
 * skips without Docker, runs in full in CI.
 *
 * <p>The replay path is driven directly via {@link ReplayScoringService} (no broker needed); it reads
 * the immutable email, the active policy, and the sender's reputation, and writes only its own
 * {@code replay_decisions}. The live reputation log/cache, feedback events, and enforced
 * classifications are snapshotted before and after and must be byte-for-byte unchanged.
 */
class SideEffectIsolationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ReplayScoringService replayScoringService;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private ReputationRepository reputation;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_replay_run_writes_only_its_own_outputs_and_leaves_live_state_untouched() {
        Email email = ingest("From: stranger@elsewhere.test\nSubject: hi\n\nplease take a look\n");
        String activePolicy = policies.findActive().orElseThrow().version();
        var run = java.util.UUID.randomUUID();
        ReplayEmailEvent event =
                ReplayEmailEvent.of(run, activePolicy, email.id(), "stranger@elsewhere.test");

        long reputationBefore = count("reputation_events");
        long feedbackBefore = count("feedback_events");
        long classificationsBefore = count("classifications");
        long sendersBefore = count("senders");

        boolean written = replayScoringService.score(event);

        // The experiment produced exactly its own output...
        assertThat(written).isTrue();
        assertThat(countReplayDecisions(run)).isEqualTo(1);
        // ...and mutated none of the live state it read.
        assertThat(count("reputation_events")).isEqualTo(reputationBefore);
        assertThat(count("feedback_events")).isEqualTo(feedbackBefore);
        assertThat(count("classifications")).isEqualTo(classificationsBefore);
        assertThat(count("senders")).isEqualTo(sendersBefore);
    }

    @Test
    void a_deliberate_live_state_write_from_an_experiment_context_is_blocked() {
        long reputationBefore = count("reputation_events");
        ReputationEvent event = ReputationEvent.of(
                "stranger@elsewhere.test", ReputationSignal.BAD, 1.0, "experiment-bug",
                ReputationBucket.AUTHENTICATED);

        assertThatThrownBy(() -> ExperimentContext.runReadOnly(() -> reputation.append(event)))
                .isInstanceOf(LiveStateWriteForbiddenException.class)
                .hasMessageContaining("reputation_events");

        // The block is preventive: no row reached the log, and the scope did not leak.
        assertThat(count("reputation_events")).isEqualTo(reputationBefore);
        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }

    private long countReplayDecisions(java.util.UUID runId) {
        Long count = jdbc.queryForObject(
                "select count(*) from replay_decisions where run_id = ?", Long.class, runId);
        return count == null ? 0 : count;
    }
}
