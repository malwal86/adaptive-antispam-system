package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The bootstrap split against a real Postgres: a labeled corpus on disk lands as a
 * leakage-free, queryable {@code eval_split_assignments} partition.
 *
 * <p>The integration suite shares one immutable {@code emails} table and the split
 * is global, so this test asserts only properties that hold regardless of what other
 * tests seeded: every one of <em>this</em> test's sender-domain families is whole, an
 * email with no ground-truth label is excluded, the globally newest/oldest families
 * land on the expected sides, and time order is monotonic across this test's families.
 * Families are pinned to far-future (2098/2099) and far-past (2001/2002) {@code Date}
 * headers so their placement is deterministic against a real-time corpus.
 */
class BootstrapEvalSplitIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String DOMAIN_SUFFIX = ".evalsplit.test";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private BootstrapEvalSplitService service;

    @Autowired
    private JdbcTemplate jdbc;

    /** Ingests one labeled member of {@code domain}'s family, dated 1 Jan {@code year}. */
    private void ingestLabeled(String domain, int year, GroundTruthLabel label) {
        UUID emailId = ingest(domain, year);
        labels.saveIfAbsent(emailId, label, "t-evalsplit-" + domain);
    }

    private UUID ingest(String domain, int year) {
        String raw = "From: sender@" + domain + DOMAIN_SUFFIX + "\n"
                + "Date: Tue, 1 Jan " + year + " 00:00:00 +0000\n"
                + "Subject: evalsplit " + domain + " " + year + "\n\n"
                + "body " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        return result.emailId();
    }

    private Map<String, Set<String>> sidesByDomain() {
        Map<String, Set<String>> byDomain = new HashMap<>();
        jdbc.query("""
                select e.sender_domain as domain, a.split_side as side
                from eval_split_assignments a
                join emails e on e.id = a.email_id
                where e.sender_domain like ?
                """, rs -> {
            byDomain.computeIfAbsent(rs.getString("domain"), k -> new HashSet<>()).add(rs.getString("side"));
        }, "%" + DOMAIN_SUFFIX);
        return byDomain;
    }

    @Test
    void materializes_a_leakage_free_split_keeping_every_family_whole() {
        // Four families across time; each family has two reworded members.
        ingestLabeled("old", 2001, GroundTruthLabel.HAM);
        ingestLabeled("old", 2001, GroundTruthLabel.HAM);
        ingestLabeled("mid", 2002, GroundTruthLabel.SPAM);
        ingestLabeled("mid", 2002, GroundTruthLabel.SPAM);
        ingestLabeled("new", 2098, GroundTruthLabel.SPAM);
        ingestLabeled("new", 2098, GroundTruthLabel.SPAM);
        ingestLabeled("newest", 2099, GroundTruthLabel.PHISH);
        ingestLabeled("newest", 2099, GroundTruthLabel.PHISH);
        // An ingested-but-unlabeled email: it must never enter the split (the eval set
        // is sourced from high-confidence labels only — story 11.03).
        ingest("unlabeled", 2099);

        SplitAudit audit = service.rebuild();

        // Grouping invariant holds globally: no family ever spans the boundary.
        assertThat(audit.crossBoundaryGroups()).isZero();

        Map<String, Set<String>> sides = sidesByDomain();
        // Every one of this test's families is whole (a single side).
        assertThat(sides.get("old" + DOMAIN_SUFFIX)).containsExactly("train");
        assertThat(sides.get("newest" + DOMAIN_SUFFIX)).containsExactly("eval");
        for (String domain : Set.of("old", "mid", "new", "newest")) {
            assertThat(sides.get(domain + DOMAIN_SUFFIX))
                    .as("family %s must be whole", domain)
                    .hasSize(1);
        }
        // The unlabeled email is absent from the split entirely.
        assertThat(sides).doesNotContainKey("unlabeled" + DOMAIN_SUFFIX);

        // Time-forward monotonicity across this test's families: no older family is
        // held out while a newer one trains.
        Map<String, Integer> yearByDomain =
                Map.of("old", 2001, "mid", 2002, "new", 2098, "newest", 2099);
        int newestTrainYear = Integer.MIN_VALUE;
        int oldestEvalYear = Integer.MAX_VALUE;
        for (var entry : yearByDomain.entrySet()) {
            Set<String> side = sides.get(entry.getKey() + DOMAIN_SUFFIX);
            if (side.contains("train")) {
                newestTrainYear = Math.max(newestTrainYear, entry.getValue());
            } else {
                oldestEvalYear = Math.min(oldestEvalYear, entry.getValue());
            }
        }
        assertThat(newestTrainYear).isLessThan(oldestEvalYear);
    }
}
