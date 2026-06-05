package com.antispam.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.AbstractPostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seed loader against a real Postgres: a labeled corpus on disk lands as
 * deduped, labeled rows reachable through the same tables the rest of the system
 * reads. Assertions are scoped by a per-test {@code dataset_source} prefix because
 * the immutable {@code emails} table is shared (and never truncated) across the
 * suite's tests.
 */
class SeedLoaderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    private Path corpus;

    private void writeMessage(String dataset, String classDir, String filename, String content) throws IOException {
        Path dir = corpus.resolve(dataset).resolve(classDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(filename), content);
    }

    /** Emails seeded for datasets matching {@code prefix}, that carry ingest_source 'seed'. */
    private long seededEmails(String prefix) {
        return jdbc.queryForObject("""
                select count(*) from emails e
                join ground_truth_labels g on e.id = g.email_id
                where g.dataset_source like ? and e.ingest_source = 'seed'
                """, Long.class, prefix + "%");
    }

    private long labeled(String prefix, GroundTruthLabel label) {
        return jdbc.queryForObject(
                "select count(*) from ground_truth_labels where dataset_source like ? and label = ?",
                Long.class, prefix + "%", label.dbValue());
    }

    @Test
    void loads_a_labeled_corpus_with_all_three_classes_counts_and_provenance() throws IOException {
        writeMessage("t1-spamassassin", "easy_ham", "ham1.eml",
                "From: alice@corp.example\nSubject: t1 lunch plans\n\nLunch at noon?");
        writeMessage("t1-spamassassin", "spam", "spam1.eml",
                "From: win@promo.example\nSubject: t1 you won a prize\n\nClaim now at the link.");
        writeMessage("t1-enron", "ham", "enron1.eml",
                "From: k.lay@enron.example\nSubject: t1 q3 numbers\n\nNumbers attached.");
        writeMessage("t1-phishtank", "phish", "batch.mbox", """
                From harvester Mon Jan 1 00:00:00 2024
                From: bank@secure-verify.example
                Subject: t1 verify your account

                Log in to avoid suspension.
                From harvester Mon Jan 1 00:01:00 2024
                From: irs@refund-now.example
                Subject: t1 tax refund pending

                Claim your refund.
                """);

        SeedReport report = seedLoader.load(corpus);

        assertThat(report.loadedByLabel())
                .containsEntry(GroundTruthLabel.HAM, 2)
                .containsEntry(GroundTruthLabel.SPAM, 1)
                .containsEntry(GroundTruthLabel.PHISH, 2);
        assertThat(report.totalLoaded()).isEqualTo(5);
        assertThat(report.duplicatesSkipped()).isZero();
        assertThat(report.datasets())
                .containsExactlyInAnyOrder("t1-spamassassin", "t1-enron", "t1-phishtank");

        // Every seeded email carries ingest_source 'seed' and a labeled provenance row.
        assertThat(seededEmails("t1-")).isEqualTo(5);
        assertThat(labeled("t1-", GroundTruthLabel.HAM)).isEqualTo(2);
        assertThat(labeled("t1-", GroundTruthLabel.SPAM)).isEqualTo(1);
        assertThat(labeled("t1-", GroundTruthLabel.PHISH)).isEqualTo(2);
    }

    @Test
    void re_running_the_seed_adds_zero_duplicate_emails() throws IOException {
        writeMessage("t2-rerun", "spam", "s.eml",
                "From: a@b.example\nSubject: t2 rerun unique body\n\none of a kind body");

        seedLoader.load(corpus);
        SeedReport second = seedLoader.load(corpus);

        assertThat(second.totalLoaded()).isZero();
        assertThat(second.duplicatesSkipped()).isEqualTo(1);
        assertThat(seededEmails("t2-")).isEqualTo(1);
    }

    @Test
    void a_missing_corpus_directory_fails_with_a_clear_message() {
        assertThatThrownBy(() -> seedLoader.load(corpus.resolve("does-not-exist")))
                .isInstanceOf(SeedException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void an_empty_corpus_directory_fails_with_a_clear_message() {
        assertThatThrownBy(() -> seedLoader.load(corpus))
                .isInstanceOf(SeedException.class)
                .hasMessageContaining("no dataset");
    }
}
