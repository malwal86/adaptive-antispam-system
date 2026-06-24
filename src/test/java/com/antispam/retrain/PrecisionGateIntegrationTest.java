package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.experiment.replay.LabeledReplayDecision;
import com.antispam.experiment.replay.ReplayDecisionRepository;
import com.antispam.experiment.replay.ReplayRun;
import com.antispam.experiment.replay.ReplayService;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Proves the precision-floor gate end-to-end against a real broker and DB (story 10.03): a candidate
 * policy is replayed over the corpus and graded by the gate on the frozen golden set. It exercises
 * what the pure {@link PrecisionFloorGateTest} cannot — that the verdict is measured via the replay
 * path on the eval-side golden set (AC 1), that a train-side email is excluded from grading (no
 * leakage), and that re-running the gate on the same run is deterministic (AC 5).
 *
 * <p>The pass/fail arithmetic is pinned deterministically in {@link PrecisionFloorGateTest}; here the
 * floor is set to a fixed value and the assertions check the gate's <em>invariant</em> against
 * whatever precision the real replay produced, plus the golden-set scoping — so the test is robust to
 * other labeled mail already in the shared corpus. {@code min-golden-samples} is dropped to 1 so the
 * small fixture is judged. Skips without Docker; runs in full in CI.
 */
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        "app.kafka.raw-topic.replication-factor=1",
        "app.kafka.raw-topic.partitions=3",
        "app.kafka.replay-topic.replication-factor=1",
        "app.kafka.replay-topic.partitions=3",
        "antispam.retrain.gate.precision-floor=0.50",
        "antispam.retrain.gate.min-golden-samples=1"
})
class PrecisionGateIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CANDIDATE = "candidate-gate-v2";
    private static final double FLOOR = 0.50;

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
    private PrecisionGateService gateService;

    @Autowired
    private ReplayDecisionRepository replayDecisions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void grades_a_candidate_on_the_golden_set_via_replay_excluding_train_and_deterministically() {
        insertCandidatePolicy();
        UUID goldenSpam = ingestLabelAndSplit("gate-spam", GroundTruthLabel.SPAM, "eval");
        UUID goldenHam = ingestLabelAndSplit("gate-ham", GroundTruthLabel.HAM, "eval");
        UUID trainSpam = ingestLabelAndSplit("gate-train-spam", GroundTruthLabel.SPAM, "train");
        Set<UUID> golden = Set.of(goldenSpam, goldenHam);

        ReplayRun run = replayService.startReplay(CANDIDATE);
        awaitGolden(run.runId(), golden);

        GateResult result = gateService.evaluate(run.runId());

        // Measured via the replay path on the candidate policy + its model (AC 1).
        assertThat(result.policyVersion()).isEqualTo(CANDIDATE);
        assertThat(result.modelVersion()).isEqualTo("bootstrap-v1");
        assertThat(result.precisionFloor()).isEqualTo(FLOOR);

        // The hard gate is precisely the floor comparison, on real replayed precision (AC 2/3).
        assertThat(result.passed()).isEqualTo(result.precision() >= FLOOR);

        // Recall/bypass/cost are reported as evidence alongside (AC 4) — the golden set has abuse.
        assertThat(result.evidence().abuseTotal()).isGreaterThanOrEqualTo(1);

        // Golden scoping: the eval-side emails are graded, the train-side spam is excluded from the
        // gate's view — a candidate is never judged on mail it could have trained on (no leakage).
        Set<UUID> gradedGolden = goldenIds(run.runId());
        assertThat(gradedGolden).contains(goldenSpam, goldenHam).doesNotContain(trainSpam);
        // ...yet the train-side email IS present in the unrestricted whole-corpus grading, proving the
        // exclusion is the golden-set filter and not a missing decision.
        assertThat(allLabeledIds(run.runId())).contains(trainSpam);

        // Determinism (AC 5): re-grading the same run yields an identical verdict.
        assertThat(gateService.evaluate(run.runId())).isEqualTo(result);
    }

    private void insertCandidatePolicy() {
        jdbc.update("""
                insert into policies (
                    version, active, warn_threshold, quarantine_threshold, block_threshold,
                    llm_threshold, model_version)
                values (?, false, 0.30, 0.60, 0.80, 0.40, 'bootstrap-v1')
                on conflict (version) do nothing
                """, CANDIDATE);
    }

    private UUID ingestLabelAndSplit(String tag, GroundTruthLabel label, String side) {
        IngestResult ingested = ingestService.ingest(emailFrom(tag + "@gate.test", tag), "api");
        jdbc.update("""
                insert into ground_truth_labels (email_id, label, dataset_source)
                values (?, ?, 'test')
                on conflict (email_id) do nothing
                """, ingested.emailId(), label.dbValue());
        jdbc.update("""
                insert into eval_split_assignments (email_id, split_side, group_key)
                values (?, ?, ?)
                on conflict (email_id) do update set split_side = excluded.split_side
                """, ingested.emailId(), side, tag);
        return ingested.emailId();
    }

    private static byte[] emailFrom(String sender, String body) {
        return ("From: " + sender + "\nSubject: hello\n\n" + body + " greetings\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void awaitGolden(UUID runId, Set<UUID> wanted) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (goldenIds(runId).containsAll(wanted)) {
                return;
            }
            sleep();
        }
        throw new AssertionError("golden replay decisions for run " + runId + " did not cover " + wanted);
    }

    private Set<UUID> goldenIds(UUID runId) {
        return replayDecisions.findLabeledByRunIdInGoldenSet(runId).stream()
                .map(LabeledReplayDecision::emailId).collect(Collectors.toSet());
    }

    private Set<UUID> allLabeledIds(UUID runId) {
        return replayDecisions.findLabeledByRunId(runId).stream()
                .map(LabeledReplayDecision::emailId).collect(Collectors.toSet());
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting golden replay decisions", e);
        }
    }
}
