package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and freezes the immutable golden benchmark in {@code golden_set_versions} /
 * {@code golden_set_members} (story 11.02). Freezing is an atomic snapshot of the current held-out
 * eval side: it copies every labeled email on {@code split_side = 'eval'} into a new version and then
 * the database makes that version unchangeable (the V32 triggers). Because the copy is taken at freeze
 * time and never re-joined, the benchmark a gate measures against stays byte-stable across model
 * versions even as the underlying split is rebuilt.
 *
 * <p>This repository never updates or deletes a version — there is no such method, and the database
 * would reject it anyway. The only write is {@link #freeze}, which is additive (a new version).
 */
@Repository
public class GoldenSetRepository {

    private static final String EVAL_SIDE_COUNT_SQL = """
            select count(*)
            from eval_split_assignments a
            join ground_truth_labels g on g.email_id = a.email_id
            where a.split_side = 'eval'
            """;

    private static final String INSERT_VERSION_SQL = """
            insert into golden_set_versions (version, eval_fraction, seed, member_count)
            values (?, ?, ?, ?)
            """;

    // The snapshot itself: copy the current eval side into the new version, duplicating the label so
    // the frozen member is self-contained (story 11.02 — the golden set never re-joins ground truth).
    private static final String FREEZE_MEMBERS_SQL = """
            insert into golden_set_members (version, email_id, label)
            select ?, a.email_id, g.label
            from eval_split_assignments a
            join ground_truth_labels g on g.email_id = a.email_id
            where a.split_side = 'eval'
            """;

    private static final String SELECT_VERSION_SQL = """
            select version, eval_fraction, seed, member_count, created_at
            from golden_set_versions
            where version = ?
            """;

    private static final String SELECT_ALL_VERSIONS_SQL = """
            select version, eval_fraction, seed, member_count, created_at
            from golden_set_versions
            order by created_at desc, version desc
            """;

    private static final String LATEST_VERSION_SQL = """
            select version
            from golden_set_versions
            order by created_at desc, version desc
            limit 1
            """;

    private static final String COUNTS_BY_LABEL_SQL = """
            select label, count(*) as n
            from golden_set_members
            where version = ?
            group by label
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public GoldenSetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Freezes the current eval side into a new immutable golden version.
     *
     * <p>Atomic: the version row and its members are written in one transaction, so a reader never sees
     * a version with a member count that disagrees with its members. The version is recorded with the
     * split configuration it was frozen under, for provenance.
     *
     * @param version      the new version's stable label — must not already exist
     * @param evalFraction the split fraction the eval side was produced under (provenance)
     * @param seed         the split seed the eval side was produced under (provenance)
     * @return the frozen version's provenance, including its member count
     */
    @Transactional
    public GoldenSetVersion freeze(String version, double evalFraction, long seed) {
        Integer memberCount = jdbc.queryForObject(EVAL_SIDE_COUNT_SQL, Integer.class);
        int count = memberCount == null ? 0 : memberCount;
        jdbc.update(INSERT_VERSION_SQL, version, evalFraction, seed, count);
        jdbc.update(FREEZE_MEMBERS_SQL, version);
        return find(version).orElseThrow(() ->
                new IllegalStateException("golden version vanished immediately after freeze: " + version));
    }

    /** Whether a version with this label has already been frozen. */
    public boolean versionExists(String version) {
        return find(version).isPresent();
    }

    /** The provenance of one version, or empty if it was never frozen. */
    public Optional<GoldenSetVersion> find(String version) {
        return jdbc.query(SELECT_VERSION_SQL, VERSION_MAPPER, version).stream().findFirst();
    }

    /** Every frozen version, newest first — the list an eval report identifies the benchmark from. */
    public List<GoldenSetVersion> findAll() {
        return jdbc.query(SELECT_ALL_VERSIONS_SQL, VERSION_MAPPER);
    }

    /** The most recently frozen version's label, or empty if none has been frozen. */
    public Optional<String> latestVersion() {
        return jdbc.query(LATEST_VERSION_SQL, (rs, n) -> rs.getString("version")).stream().findFirst();
    }

    /** Per-class member counts of one frozen version, for surfacing its balance in a report. */
    public Map<GroundTruthLabel, Long> countsByLabel(String version) {
        Map<GroundTruthLabel, Long> counts = new EnumMap<>(GroundTruthLabel.class);
        jdbc.query(COUNTS_BY_LABEL_SQL, rs -> {
            counts.put(GroundTruthLabel.fromDbValue(rs.getString("label")), rs.getLong("n"));
        }, version);
        return counts;
    }

    private static final RowMapper<GoldenSetVersion> VERSION_MAPPER = (rs, n) -> new GoldenSetVersion(
            rs.getString("version"),
            rs.getDouble("eval_fraction"),
            rs.getLong("seed"),
            rs.getInt("member_count"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
