package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 11.01 enrichment against a real Postgres: the split must group by the two
 * real lineage signals — campaign-cluster membership (06.03) and arena mutation
 * lineage (08.01) — not just sender domain. Each scenario deliberately spreads a
 * family across <em>different</em> sender domains, so a split that still keeps the
 * family whole can only have done so by honoring the cluster/lineage relation.
 *
 * <p>Like the bootstrap IT, the suite shares one immutable {@code emails} table and a
 * global split, so the assertions are local: this test's own family lands on a single
 * side, regardless of what else the corpus holds.
 */
class EvalSplitLineageGroupingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String MODEL_VERSION = "eval-lineage-test";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private BootstrapEvalSplitService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID ingestLabeled(String domain, int year, GroundTruthLabel label) {
        String raw = "From: sender@" + domain + "\n"
                + "Date: Tue, 1 Jan " + year + " 00:00:00 +0000\n"
                + "Subject: lineage " + domain + " " + year + "\n\n"
                + "body " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, "t-lineage-" + UUID.randomUUID());
        return result.emailId();
    }

    private String sideOf(UUID emailId) {
        return jdbc.queryForObject(
                "select split_side from eval_split_assignments where email_id = ?", String.class, emailId);
    }

    @Test
    void a_campaign_cluster_spanning_two_domains_never_spans_the_split_boundary() {
        // Two reworded variants offline clustering grouped, but sent from different
        // domains — sender-domain grouping alone would let them straddle the boundary.
        UUID a = ingestLabeled("cluster-a.invalid", 2001, GroundTruthLabel.SPAM);
        UUID b = ingestLabeled("cluster-b.invalid", 2099, GroundTruthLabel.SPAM);

        UUID clusterId = UUID.randomUUID();
        String zeroVector = "[" + IntStream.range(0, 128).mapToObj(i -> "0").reduce((x, y) -> x + "," + y).orElseThrow() + "]";
        jdbc.update("insert into campaign_clusters (id, model_version, centroid_embedding_id, size) "
                + "values (?, ?, ?::vector, ?)", clusterId, MODEL_VERSION, zeroVector, 2);
        jdbc.update("insert into campaign_cluster_members (cluster_id, email_id, model_version, similarity) "
                + "values (?, ?, ?, ?)", clusterId, a, MODEL_VERSION, 1.0);
        jdbc.update("insert into campaign_cluster_members (cluster_id, email_id, model_version, similarity) "
                + "values (?, ?, ?, ?)", clusterId, b, MODEL_VERSION, 1.0);

        SplitAudit audit = service.rebuild();

        assertThat(audit.crossBoundaryGroups()).isZero();
        assertThat(sideOf(a)).isEqualTo(sideOf(b));
    }

    @Test
    void an_arena_variant_never_trains_while_its_seed_is_judged() {
        // A real seed and a mutated variant from a different domain: the lineage row
        // binds them into one family so the near-twin can never leak across the split.
        UUID seed = ingestLabeled("seed-domain.invalid", 2001, GroundTruthLabel.SPAM);
        UUID variant = ingestLabeled("variant-domain.invalid", 2099, GroundTruthLabel.SPAM);

        jdbc.update("""
                insert into adversarial_emails (
                    id, variant_email_id, seed_email_id, parent_variant_id,
                    mutation_strategy, ground_truth_label, attacker_model, run_id, generation)
                values (?, ?, ?, null, 'synonym', 'spam', 'test-attacker', ?, 1)
                """, UUID.randomUUID(), variant, seed, UUID.randomUUID());

        SplitAudit audit = service.rebuild();

        assertThat(audit.crossBoundaryGroups()).isZero();
        assertThat(sideOf(variant)).isEqualTo(sideOf(seed));
    }
}
