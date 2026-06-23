package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Proves the replay slice end-to-end against a real broker (story 09.01): the immutable corpus is
 * re-published to {@code emails.replay}, an experimental consumer scores it under the chosen policy,
 * and the verdicts land in {@code replay_decisions} — deterministically across runs and without
 * minting any live {@code classifications}. Postgres comes from the shared base; a Kafka broker is
 * added here and the spine is re-enabled (off in the default test profile). The bootstrap policy
 * seeded by migration is the chosen policy.
 *
 * <p>Assertions are scoped to the emails this test ingests, so it is robust to other emails already
 * in the shared corpus. Skips without Docker; runs in full in CI.
 */
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        "app.kafka.raw-topic.replication-factor=1",
        "app.kafka.raw-topic.partitions=3",
        "app.kafka.replay-topic.replication-factor=1",
        "app.kafka.replay-topic.partitions=3"
})
class ReplayIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String POLICY = "bootstrap-v1";

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            KAFKA.start();
        }
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ReplayService replayService;

    @Autowired
    private ReplayDecisionRepository replayDecisions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void replays_the_corpus_into_replay_decisions_tagged_by_run_and_policy() {
        Set<UUID> mine = ingestThree("recall");

        ReplayRun run = replayService.startReplay(POLICY);

        Map<UUID, ScoredDecision> scored = awaitScored(run.runId(), mine);
        assertThat(scored.keySet()).containsAll(mine);
        assertThat(scored.values()).allSatisfy(d -> assertThat(d.policyVersion()).isEqualTo(POLICY));
    }

    @Test
    void replays_the_same_corpus_and_policy_deterministically() {
        Set<UUID> mine = ingestThree("determinism");

        ReplayRun runA = replayService.startReplay(POLICY);
        Map<UUID, ScoredDecision> a = awaitScored(runA.runId(), mine);
        ReplayRun runB = replayService.startReplay(POLICY);
        Map<UUID, ScoredDecision> b = awaitScored(runB.runId(), mine);

        // Same corpus + same policy → identical verdict (decision + route) per email. We assert the
        // verdict, not the raw posterior float: the scorer's exact-output determinism is pinned by
        // PolicyScorerTest with fixed inputs, whereas here the ingested emails accrue reputation
        // asynchronously on emails.raw, so the posterior can shift by a hair between back-to-back
        // runs without changing the tier — the end-to-end determinism the slice actually promises.
        for (UUID id : mine) {
            assertThat(b.get(id).decision()).isEqualTo(a.get(id).decision());
            assertThat(b.get(id).route()).isEqualTo(a.get(id).route());
        }
    }

    @Test
    void does_not_mint_live_classifications_for_replayed_emails() {
        Set<UUID> mine = ingestThree("isolation");

        ReplayRun run = replayService.startReplay(POLICY);
        awaitScored(run.runId(), mine);

        // Replay wrote experiment-scoped rows but never an enforced classification (AC 5).
        assertThat(classificationCountFor(mine)).isZero();
        assertThat(replayDecisions.findByRunId(run.runId())).isNotEmpty();
    }

    /** Ingests three distinct emails and returns their canonical ids. */
    private Set<UUID> ingestThree(String tag) {
        return List.of("alice", "bob", "carol").stream()
                .map(who -> ingestService.ingest(emailFrom(who + "@" + tag + ".test", tag), "api"))
                .map(IngestResult::emailId)
                .collect(Collectors.toSet());
    }

    private static byte[] emailFrom(String sender, String body) {
        return ("From: " + sender + "\nSubject: hello\n\n" + body + " greetings\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Polls {@code replay_decisions} until every id in {@code wanted} has been scored under
     * {@code runId}, or fails after a bounded wait. Returns the scored decisions keyed by email id.
     */
    private Map<UUID, ScoredDecision> awaitScored(UUID runId, Set<UUID> wanted) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Map<UUID, ScoredDecision> scored = replayDecisions.findByRunId(runId).stream()
                    .collect(Collectors.toMap(ReplayDecision::emailId, ReplayDecision::scored,
                            (first, second) -> first));
            if (scored.keySet().containsAll(wanted)) {
                return scored;
            }
            sleep();
        }
        throw new AssertionError("replay decisions for run " + runId + " did not cover " + wanted);
    }

    private long classificationCountFor(Set<UUID> ids) {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement(
                    "select count(*) from classifications where email_id = any(?)");
            ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting replay decisions", e);
        }
    }
}
