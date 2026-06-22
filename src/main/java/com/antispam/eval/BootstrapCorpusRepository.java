package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads the labeled seed corpus into {@link SplitItem}s for the bootstrap split.
 * This is the special-purpose adapter that decides, for Phase 2, what stands in for
 * the not-yet-existent campaign clusters (Epic 06) and arena lineage (Epic 08):
 *
 * <ul>
 *   <li><b>Family proxy</b> = the sender domain. Templated and reworded blasts
 *       overwhelmingly reuse a sending domain, so grouping by it keeps near-duplicate
 *       variants on one side. Emails with no domain fall through as {@code null} and
 *       the splitter makes each its own singleton.</li>
 *   <li><b>Timeline</b> = the email's {@code received_at}, falling back to
 *       {@code ingested_at} (never null) so the splitter always has a time.</li>
 *   <li><b>Source</b> = {@code ground_truth_labels} only. These are the high-confidence
 *       corpus labels; simulator feedback lives elsewhere and so cannot enter the eval
 *       set (story 11.03).</li>
 * </ul>
 *
 * <p>Phase 4 swaps this adapter's {@code groupId} for a real lineage id without the
 * splitter changing.
 */
@Repository
public class BootstrapCorpusRepository {

    private static final String SELECT_CORPUS_SQL = """
            select g.email_id                          as email_id,
                   e.sender_domain                     as group_id,
                   coalesce(e.received_at, e.ingested_at) as event_time,
                   g.label                             as label
            from ground_truth_labels g
            join emails e on e.id = g.email_id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public BootstrapCorpusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every labeled email as a split item, family-keyed by sender domain. */
    public List<SplitItem> loadCorpus() {
        return jdbc.query(SELECT_CORPUS_SQL, ITEM_MAPPER);
    }

    private static final RowMapper<SplitItem> ITEM_MAPPER = (rs, rowNum) -> new SplitItem(
            rs.getObject("email_id", UUID.class),
            rs.getString("group_id"),
            rs.getObject("event_time", OffsetDateTime.class).toInstant(),
            GroundTruthLabel.fromDbValue(rs.getString("label")));
}
