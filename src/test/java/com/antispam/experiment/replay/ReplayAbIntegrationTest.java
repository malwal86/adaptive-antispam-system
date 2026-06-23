package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Proves the replay A/B harness end-to-end against a real broker and DB (story 09.04): two policies
 * replayed over the same fixed labeled corpus, graded against ground truth into a comparison the
 * promotion gate consumes. It exercises the parts the pure metric unit tests cannot: the
 * decision↔ground-truth join ({@code findLabeledByRunId}), determinism across re-runs through the
 * real Kafka path (AC 3), and side-effect isolation (AC 5 / story 09.03) — no live classifications.
 *
 * <p>The exact metric arithmetic is pinned deterministically in {@link PolicyMetricsTest}; here the
 * assertions are scoped to the emails this test ingests so they are robust to other labeled mail
 * already in the shared corpus. Skips without Docker; runs in full in CI.
 */
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        "app.kafka.raw-topic.replication-factor=1",
        "app.kafka.raw-topic.partitions=3",
        "app.kafka.replay-topic.replication-factor=1",
        "app.kafka.replay-topic.partitions=3"
})
class ReplayAbIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASELINE = "bootstrap-v1";
    private static final String STRICT = "strict-ab-v2";

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
    private ReplayAbService abService;

    @Autowired
    private ReplayDecisionRepository replayDecisions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void grades_the_labeled_corpus_under_two_policies_deterministically_and_in_isolation() {
        insertStrictPolicy();
        UUID spam = ingestAndLabel("ab-spam", GroundTruthLabel.SPAM);
        UUID ham = ingestAndLabel("ab-ham", GroundTruthLabel.HAM);
        Set<UUID> mine = Set.of(spam, ham);

        ReplayAbRun first = abService.startAb(BASELINE, STRICT);
        awaitLabeled(first.runIdA(), mine);
        awaitLabeled(first.runIdB(), mine);

        // The comparison is graded against ground truth and tagged by policy + model version (AC 1/2).
        ComparisonReport report = abService.compare(first.runIdA(), first.runIdB());
        assertThat(report.policyA().policyVersion()).isEqualTo(BASELINE);
        assertThat(report.policyA().modelVersion()).isEqualTo("bootstrap-v1");
        assertThat(report.policyB().policyVersion()).isEqualTo(STRICT);
        assertThat(report.policyB().modelVersion()).isEqualTo("bootstrap-v1");
        // Both policies graded the same fixed corpus — at least my two labeled emails, equal totals.
        assertThat(report.policyA().total()).isGreaterThanOrEqualTo(2);
        assertThat(report.policyB().total()).isEqualTo(report.policyA().total());

        // The join is correct: my spam appears under the baseline run, labeled SPAM, with a verdict.
        Map<UUID, LabeledReplayDecision> baselineMine = labeledFor(first.runIdA(), mine);
        assertThat(baselineMine.get(spam).label()).isEqualTo(GroundTruthLabel.SPAM);
        assertThat(baselineMine.get(ham).label()).isEqualTo(GroundTruthLabel.HAM);
        assertThat(baselineMine.get(spam).decision()).isNotNull();

        // Determinism (AC 3): re-running the same A/B grades my emails identically under each policy.
        ReplayAbRun second = abService.startAb(BASELINE, STRICT);
        awaitLabeled(second.runIdA(), mine);
        awaitLabeled(second.runIdB(), mine);
        assertThat(verdicts(second.runIdA(), mine)).isEqualTo(verdicts(first.runIdA(), mine));
        assertThat(verdicts(second.runIdB(), mine)).isEqualTo(verdicts(first.runIdB(), mine));

        // Isolation (AC 5 / story 09.03): replaying the corpus minted no enforced classifications.
        assertThat(classificationCountFor(mine)).isZero();
    }

    /** A strict regime distinct from the active one, so the A/B compares two genuine policies. */
    private void insertStrictPolicy() {
        jdbc.update("""
                insert into policies (
                    version, active, warn_threshold, quarantine_threshold, block_threshold,
                    llm_threshold, model_version)
                values (?, false, 0.0, 0.0, 0.0, 0.40, 'bootstrap-v1')
                on conflict (version) do nothing
                """, STRICT);
    }

    private UUID ingestAndLabel(String tag, GroundTruthLabel label) {
        IngestResult ingested = ingestService.ingest(
                emailFrom(tag + "@ab.test", tag), "api");
        jdbc.update("""
                insert into ground_truth_labels (email_id, label, dataset_source)
                values (?, ?, 'test')
                on conflict (email_id) do nothing
                """, ingested.emailId(), label.dbValue());
        return ingested.emailId();
    }

    private static byte[] emailFrom(String sender, String body) {
        return ("From: " + sender + "\nSubject: hello\n\n" + body + " greetings\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Polls the labeled join until it covers {@code wanted}, then returns those rows keyed by email. */
    private Map<UUID, LabeledReplayDecision> awaitLabeled(UUID runId, Set<UUID> wanted) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Map<UUID, LabeledReplayDecision> got = labeledFor(runId, wanted);
            if (got.keySet().containsAll(wanted)) {
                return got;
            }
            sleep();
        }
        throw new AssertionError("labeled replay decisions for run " + runId + " did not cover " + wanted);
    }

    private Map<UUID, LabeledReplayDecision> labeledFor(UUID runId, Set<UUID> wanted) {
        return replayDecisions.findLabeledByRunId(runId).stream()
                .filter(d -> wanted.contains(d.emailId()))
                .collect(Collectors.toMap(LabeledReplayDecision::emailId, d -> d, (a, b) -> a));
    }

    /** My emails' (decision, route) verdicts under one run — the determinism-comparable projection. */
    private Map<UUID, Map.Entry<Decision, RouteUsed>> verdicts(UUID runId, Set<UUID> wanted) {
        return labeledFor(runId, wanted).entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> Map.entry(e.getValue().decision(), e.getValue().route())));
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
            throw new AssertionError("interrupted while awaiting labeled replay decisions", e);
        }
    }
}
