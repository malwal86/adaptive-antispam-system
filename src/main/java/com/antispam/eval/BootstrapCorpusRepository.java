package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the labeled seed corpus into {@link SplitItem}s for the train/eval split,
 * resolving each email's family from every grouping relation the spine carries.
 *
 * <p>The bootstrap (Phase 2) keyed a family by sender domain alone, standing in for
 * the not-yet-existent campaign clusters and arena lineage. Story 11.01's enrichment
 * (Phase 4) now feeds three relations into the {@link CorpusGroupResolver}, which
 * unions them into one indivisible family per email:
 *
 * <ul>
 *   <li><b>Campaign cluster membership</b> ({@code campaign_cluster_members}, Epic
 *       06.03) — the reworded variants offline clustering grouped.</li>
 *   <li><b>Arena mutation lineage</b> ({@code adversarial_emails}, Epic 08.01) — each
 *       minted variant paired with the real seed it descends from.</li>
 *   <li><b>Sender domain</b> — the original proxy, retained as the fallback that still
 *       groups templated blasts no cluster or lineage covers.</li>
 * </ul>
 *
 * <p>The other two adapter decisions are unchanged: the timeline is {@code received_at}
 * falling back to {@code ingested_at} (never null), and the label source is
 * {@code ground_truth_labels} only — the high-confidence corpus labels — so simulator
 * feedback can never enter the eval set (story 11.03). Because grouping is resolved
 * here and the splitter consumes a plain {@code groupId}, none of this enrichment
 * touches the splitter's leakage-free logic.
 */
@Repository
public class BootstrapCorpusRepository {

    private static final String SELECT_CORPUS_SQL = """
            select g.email_id                          as email_id,
                   e.sender_domain                     as sender_domain,
                   coalesce(e.received_at, e.ingested_at) as event_time,
                   g.label                             as label
            from ground_truth_labels g
            join emails e on e.id = g.email_id
            """;

    // The two real lineage relations (stories 06.03 / 08.01). Both endpoints are read
    // unfiltered: a node outside the labeled corpus (a real spam seed that itself was
    // never labeled) is still a valid connector that chains two of its variants into
    // one family — the resolver unions through it but keys only corpus emails.
    private static final String SELECT_CLUSTER_MEMBERS_SQL = """
            select cluster_id as cluster_id, email_id as email_id
            from campaign_cluster_members
            """;

    private static final String SELECT_LINEAGE_PAIRS_SQL = """
            select variant_email_id as variant_email_id, seed_email_id as seed_email_id
            from adversarial_emails
            """;

    private final JdbcTemplate jdbc;
    private final CorpusGroupResolver groupResolver;

    @Autowired
    public BootstrapCorpusRepository(JdbcTemplate jdbc, CorpusGroupResolver groupResolver) {
        this.jdbc = jdbc;
        this.groupResolver = groupResolver;
    }

    /**
     * Every labeled email as a split item, family-keyed by the union of its campaign,
     * lineage, and sender-domain relations.
     */
    public List<SplitItem> loadCorpus() {
        List<CorpusRow> rows = jdbc.query(SELECT_CORPUS_SQL, (rs, n) -> new CorpusRow(
                rs.getObject("email_id", UUID.class),
                rs.getString("sender_domain"),
                rs.getObject("event_time", OffsetDateTime.class).toInstant(),
                GroundTruthLabel.fromDbValue(rs.getString("label"))));

        Map<UUID, String> familyKeys = resolveFamilies(rows);

        List<SplitItem> items = new ArrayList<>(rows.size());
        for (CorpusRow row : rows) {
            items.add(new SplitItem(row.emailId(), familyKeys.get(row.emailId()), row.time(), row.label()));
        }
        return items;
    }

    /** Gathers the three grouping relations and unions them into one key per email. */
    private Map<UUID, String> resolveFamilies(List<CorpusRow> rows) {
        Set<UUID> universe = new HashSet<>(rows.size() * 2);
        Map<String, List<UUID>> byDomain = new LinkedHashMap<>();
        for (CorpusRow row : rows) {
            universe.add(row.emailId());
            String domain = row.senderDomain();
            if (domain != null && !domain.isBlank()) {
                byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(row.emailId());
            }
        }

        List<List<UUID>> relations = new ArrayList<>(byDomain.values());
        relations.addAll(clusterRelations());
        relations.addAll(lineageRelations());
        return groupResolver.resolve(universe, relations);
    }

    /** Each campaign cluster's members as one relation group. */
    private List<List<UUID>> clusterRelations() {
        Map<UUID, List<UUID>> byCluster = new LinkedHashMap<>();
        jdbc.query(SELECT_CLUSTER_MEMBERS_SQL, rs -> {
            UUID clusterId = rs.getObject("cluster_id", UUID.class);
            UUID emailId = rs.getObject("email_id", UUID.class);
            byCluster.computeIfAbsent(clusterId, k -> new ArrayList<>()).add(emailId);
        });
        return new ArrayList<>(byCluster.values());
    }

    /** Each variant paired with the seed it descends from as one relation group. */
    private List<List<UUID>> lineageRelations() {
        List<List<UUID>> pairs = new ArrayList<>();
        jdbc.query(SELECT_LINEAGE_PAIRS_SQL, rs -> {
            pairs.add(List.of(
                    rs.getObject("variant_email_id", UUID.class),
                    rs.getObject("seed_email_id", UUID.class)));
        });
        return pairs;
    }

    /** A labeled corpus row before its family is resolved. */
    private record CorpusRow(UUID emailId, String senderDomain, Instant time, GroundTruthLabel label) {
    }
}
