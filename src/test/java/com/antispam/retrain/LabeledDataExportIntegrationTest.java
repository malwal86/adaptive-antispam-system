package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the labeled-data export end-to-end against the real database (story 10.01): the three label
 * sources combine with their weights and provenance, every example is tied to the feature version, and
 * — the crucial guarantee — nothing on the golden eval side leaks into training, including a feedback
 * label sitting on an eval-side email. Also proves the export reproduces deterministically from the
 * same snapshot. Skips without Docker.
 */
class LabeledDataExportIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private LabeledDataExportService service;
    @Autowired
    private IngestService ingestService;
    @Autowired
    private GroundTruthLabelRepository groundTruth;
    @Autowired
    private RetrainLabelRepository retrainLabels;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void combines_the_sources_with_weights_and_provenance_and_excludes_the_golden_eval_set() {
        // A train-side seed label → exported as 'seed' at full confidence.
        UUID seedTrain = ingest("seed-train");
        groundTruth.saveIfAbsent(seedTrain, GroundTruthLabel.SPAM, "spamassassin");
        assignSplit(seedTrain, "train");

        // An eval-side seed label → the golden judging set, must NOT be exported.
        UUID seedEval = ingest("seed-eval");
        groundTruth.saveIfAbsent(seedEval, GroundTruthLabel.HAM, "enron");
        assignSplit(seedEval, "eval");

        // A weighted feedback label (07.03), unassigned → exported with its gate weight preserved.
        UUID feedbackEmail = ingest("feedback");
        retrainLabels.saveAll(List.of(new RetrainLabel(UUID.randomUUID(), feedbackEmail,
                GroundTruthLabel.SPAM, 0.7, "feedback", "{\"corroborators\":3}")));

        // An arena ground-truth label (08.04), unassigned → exported as 'arena'.
        UUID arenaEmail = ingest("arena");
        retrainLabels.saveAll(List.of(new RetrainLabel(UUID.randomUUID(), arenaEmail,
                GroundTruthLabel.PHISH, 1.0, "arena", "{\"outcome\":\"bypass\"}")));

        // A feedback label sitting on an eval-side email → must be excluded too (no leak via feedback).
        UUID feedbackOnEval = ingest("feedback-on-eval");
        groundTruth.saveIfAbsent(feedbackOnEval, GroundTruthLabel.HAM, "enron");
        assignSplit(feedbackOnEval, "eval");
        retrainLabels.saveAll(List.of(new RetrainLabel(UUID.randomUUID(), feedbackOnEval,
                GroundTruthLabel.HAM, 0.9, "feedback", "{\"corroborators\":2}")));

        TrainingExport export = service.export();
        List<TrainingExample> mine = export.examples().stream()
                .filter(e -> Set.of(seedTrain, seedEval, feedbackEmail, arenaEmail, feedbackOnEval)
                        .contains(e.emailId()))
                .toList();

        // The three trainable labels are present; both eval-side emails (seed and feedback-on-eval) are gone.
        assertThat(mine).extracting(TrainingExample::emailId)
                .containsExactlyInAnyOrder(seedTrain, feedbackEmail, arenaEmail)
                .doesNotContain(seedEval, feedbackOnEval);

        // Each source carries its weight and provenance through, tied to the current feature version.
        assertThat(byEmail(mine, seedTrain)).satisfies(e -> {
            assertThat(e.source()).isEqualTo("seed");
            assertThat(e.weight()).isEqualTo(1.0);
            assertThat(e.provenance()).contains("spamassassin"); // json_build_object('datasetSource', ...)
        });
        assertThat(byEmail(mine, feedbackEmail)).satisfies(e -> {
            assertThat(e.source()).isEqualTo("feedback");
            assertThat(e.weight()).isEqualTo(0.7); // gate weight preserved (AC 2)
        });
        assertThat(byEmail(mine, arenaEmail)).satisfies(e -> {
            assertThat(e.source()).isEqualTo("arena");
            assertThat(e.label()).isEqualTo(GroundTruthLabel.PHISH);
        });
        assertThat(mine).allSatisfy(e ->
                assertThat(e.featureVersion()).isEqualTo(EmailFeatureExtractor.FEATURE_VERSION));

        // Leakage check (AC 4 / success metric): intersection(export, golden eval) == ∅.
        Set<UUID> exported = export.examples().stream()
                .map(TrainingExample::emailId).collect(Collectors.toSet());
        assertThat(exported).doesNotContainAnyElementsOf(evalEmailIds());
    }

    @Test
    void de_identifies_senders_to_a_stable_pseudonym_with_no_raw_identifiers_in_the_artifact() {
        // Two labels from the SAME sender, plus one from a different sender. The feedback gate
        // writes the real senderKey into provenance — exactly the identifier the export must scrub.
        UUID first = ingestFrom("alice@example.com", "deid-a1");
        UUID second = ingestFrom("alice@example.com", "deid-a2");
        UUID other = ingestFrom("bob@elsewhere.example", "deid-b1");
        retrainLabels.saveAll(List.of(
                new RetrainLabel(UUID.randomUUID(), first, GroundTruthLabel.SPAM, 0.7, "feedback",
                        "{\"senderKey\":\"alice@example.com\"}"),
                new RetrainLabel(UUID.randomUUID(), second, GroundTruthLabel.SPAM, 0.7, "feedback",
                        "{\"senderKey\":\"alice@example.com\"}"),
                new RetrainLabel(UUID.randomUUID(), other, GroundTruthLabel.SPAM, 0.7, "feedback",
                        "{\"senderKey\":\"bob@elsewhere.example\"}")));

        List<TrainingExample> mine = service.export().examples().stream()
                .filter(e -> Set.of(first, second, other).contains(e.emailId()))
                .toList();

        // No raw direct identifier survives anywhere in the exported rows (success metric).
        assertThat(mine).allSatisfy(e -> {
            assertThat(e.senderPseudonym()).startsWith("snd_");
            assertThat(e.senderPseudonym() + e.provenance())
                    .doesNotContain("alice@example.com")
                    .doesNotContain("bob@elsewhere.example");
        });

        // Grouping integrity: same-sender rows share a pseudonym; a different sender differs.
        String aliceFirst = byEmail(mine, first).senderPseudonym();
        String aliceSecond = byEmail(mine, second).senderPseudonym();
        String bob = byEmail(mine, other).senderPseudonym();
        assertThat(aliceFirst).isEqualTo(aliceSecond).isNotEqualTo(bob);

        // The pseudonym written into provenance matches the one the export groups by.
        assertThat(byEmail(mine, first).provenance()).contains(aliceFirst);
    }

    @Test
    void reproduces_deterministically_from_the_same_snapshot() {
        UUID seed = ingest("det-seed");
        groundTruth.saveIfAbsent(seed, GroundTruthLabel.SPAM, "spamassassin");
        assignSplit(seed, "train");
        UUID feedback = ingest("det-feedback");
        retrainLabels.saveAll(List.of(new RetrainLabel(UUID.randomUUID(), feedback,
                GroundTruthLabel.SPAM, 0.5, "feedback", "{\"k\":1}")));

        // Same DB snapshot → byte-identical export (AC 5): same examples in the same order.
        assertThat(service.export().examples()).isEqualTo(service.export().examples());
    }

    private TrainingExample byEmail(List<TrainingExample> examples, UUID emailId) {
        return examples.stream().filter(e -> e.emailId().equals(emailId)).findFirst().orElseThrow();
    }

    private Set<UUID> evalEmailIds() {
        return Set.copyOf(jdbc.queryForList(
                "select email_id from eval_split_assignments where split_side = 'eval'", UUID.class));
    }

    private void assignSplit(UUID emailId, String side) {
        jdbc.update("insert into eval_split_assignments (email_id, split_side, group_key) values (?, ?, ?)",
                emailId, side, "test-group");
    }

    private UUID ingest(String tag) {
        return ingestFrom("s@" + tag + ".test", tag);
    }

    private UUID ingestFrom(String sender, String tag) {
        byte[] raw = ("From: " + sender + "\nSubject: " + tag + "\n\nbody " + tag + "\n")
                .getBytes(StandardCharsets.UTF_8);
        IngestResult ingested = ingestService.ingestOffSpine(raw, "seed");
        return ingested.emailId();
    }
}
