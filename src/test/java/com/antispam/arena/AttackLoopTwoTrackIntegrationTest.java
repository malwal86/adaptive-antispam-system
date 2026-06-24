package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the two-track arena end-to-end against the real database (story 08.02b): one run mutates both
 * real abuse seeds (Track A, recall stress) and real legit mail (Track B, precision stress), the legit
 * mutations stay ham, the run reports a recall bypass rate and a precision false-positive rate
 * separately (AC 3), every scored variant carries the fixed defender's verdict, and no live
 * classification is minted (isolation, story 09.03). The attacker is a deterministic stub bean, so the
 * slice runs keyless. Skips without Docker.
 */
class AttackLoopTwoTrackIntegrationTest extends AbstractPostgresIntegrationTest {

    /** A deterministic attacker so the loop needs no provider: it perturbs by tagging per generation. */
    @TestConfiguration
    static class StubAttacker {
        @Bean
        @Primary
        AttackerPort stubAttackerPort() {
            return (strategy, seedContent) ->
                    "X-Mutation: " + strategy.dbValue() + " " + UUID.randomUUID() + "\n" + seedContent;
        }
    }

    @Autowired
    private AttackLoopService loop;

    @Autowired
    private AdversarialEmailRepository variants;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runs_both_tracks_and_reports_recall_and_precision_pressure_separately() {
        UUID spamSeed = seed("twotrackS", "spammer@evil.test",
                "Subject: free money\n\nClaim your prize at http://evil.test now\n", GroundTruthLabel.SPAM);
        UUID legitSeed = seed("twotrackH", "colleague@work.example",
                "Subject: lunch\n\nWant to grab lunch at noon? Let me know.\n", GroundTruthLabel.HAM);

        AdversarialRun run = loop.run(new AttackRunConfig(
                List.of(spamSeed), List.of(legitSeed),
                List.of(MutationStrategy.SYNONYM, MutationStrategy.STRUCTURE),
                0.5, 2, new BigDecimal("1.00")));

        // Both tracks ran, so both metrics are produced — recall and precision pressure side by side
        // (AC 3). A spam-only arena would report only the first.
        assertThat(run.actualBypassRate()).isNotNull().isBetween(0.0, 1.0);
        assertThat(run.precisionFpRate()).isNotNull().isBetween(0.0, 1.0);

        List<AdversarialEmail> minted = variants.findByRun(run.id());
        // Track A produced abuse variants; Track B produced legit variants that stayed ham (AC 2).
        assertThat(minted).filteredOn(v -> v.seedEmailId().equals(spamSeed))
                .isNotEmpty()
                .allSatisfy(v -> {
                    assertThat(v.label()).isEqualTo(GroundTruthLabel.SPAM);
                    assertThat(v.track()).isEqualTo(Track.SPAM);
                    assertThat(v.defenderDelivered()).isNotNull();
                });
        assertThat(minted).filteredOn(v -> v.seedEmailId().equals(legitSeed))
                .isNotEmpty()
                .allSatisfy(v -> {
                    assertThat(v.label()).isEqualTo(GroundTruthLabel.HAM);
                    assertThat(v.track()).isEqualTo(Track.LEGIT);
                    assertThat(v.defenderDelivered()).isNotNull();
                });

        // Isolation (story 09.03): scoring the variants minted no enforced classification for them.
        assertThat(classificationCountFor(minted.stream().map(AdversarialEmail::variantEmailId).toList()))
                .isZero();
    }

    private UUID seed(String tag, String from, String body, GroundTruthLabel label) {
        byte[] raw = ("From: " + from + "\nX-Seed: " + tag + "\n" + body).getBytes(StandardCharsets.UTF_8);
        IngestResult ingested = ingestService.ingestOffSpine(raw, "seed");
        labels.saveIfAbsent(ingested.emailId(), label, "test-corpus");
        return ingested.emailId();
    }

    private long classificationCountFor(List<UUID> ids) {
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
}
