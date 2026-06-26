package com.antispam.eval;

import com.antispam.common.JdbcTimestamps;
import com.antispam.seed.GroundTruthLabel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and reads the materialized train/eval split in
 * {@code eval_split_assignments}. A rebuild is whole-corpus: {@link #replaceAll}
 * clears the prior split and writes the new one in a single transaction, so a reader
 * never sees two overlapping splits or a half-written one.
 */
@Repository
public class EvalSplitRepository {

    private static final String DELETE_ALL_SQL = "delete from eval_split_assignments";

    private static final String INSERT_SQL = """
            insert into eval_split_assignments (email_id, split_side, group_key)
            values (?, ?, ?)
            """;

    private static final String COUNTS_BY_SIDE_AND_LABEL_SQL = """
            select a.split_side as split_side, g.label as label, count(*) as n
            from eval_split_assignments a
            join ground_truth_labels g on g.email_id = a.email_id
            group by a.split_side, g.label
            """;

    // Re-derives the leakage-free evidence from the MATERIALIZED split (story 11.03), so the
    // time-forward metric can be cited without recomputing the split. It re-checks the durable table
    // rather than trusting the splitter that wrote it: a family whose group_key appears on both sides
    // is a grouping bug (cross_boundary, must be 0), and an eval email older than the newest train
    // email is honest time-forward overlap (temporal_inversions). The timeline is received_at falling
    // back to ingested_at, exactly as the splitter saw it.
    private static final String STORED_AUDIT_SQL = """
            with rows as (
                select a.split_side                          as side,
                       a.group_key                           as gk,
                       coalesce(e.received_at, e.ingested_at) as et
                from eval_split_assignments a
                join emails e on e.id = a.email_id
            ),
            agg as (
                select count(*)                                  as total,
                       count(*) filter (where side = 'train')    as train_count,
                       count(*) filter (where side = 'eval')     as eval_count,
                       count(distinct gk)                        as group_count,
                       max(et) filter (where side = 'train')     as latest_train_time,
                       min(et) filter (where side = 'eval')      as earliest_eval_time
                from rows
            ),
            cross_boundary as (
                select count(*) as n
                from (select gk from rows group by gk having count(distinct side) > 1) spanning
            ),
            inversions as (
                select count(*) as n
                from rows, agg
                where rows.side = 'eval'
                  and agg.latest_train_time is not null
                  and rows.et < agg.latest_train_time
            )
            select agg.total, agg.train_count, agg.eval_count, agg.group_count,
                   cross_boundary.n as cross_boundary, inversions.n as temporal_inversions,
                   agg.latest_train_time, agg.earliest_eval_time
            from agg, cross_boundary, inversions
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public EvalSplitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces the stored split with {@code split}'s assignments, atomically. The
     * delete-then-insert runs in one transaction so the table is never momentarily
     * empty or doubled for a concurrent reader.
     */
    @Transactional
    public void replaceAll(EvalSplit split) {
        jdbc.update(DELETE_ALL_SQL);
        List<Map.Entry<UUID, SplitSide>> rows = new ArrayList<>(split.sides().entrySet());
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                UUID emailId = rows.get(i).getKey();
                ps.setObject(1, emailId);
                ps.setString(2, rows.get(i).getValue().dbValue());
                ps.setString(3, split.groups().get(emailId));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /**
     * Re-derives the leakage-free {@link SplitAudit} from the materialized split (story 11.03), so the
     * time-forward metric is a read, not a recompute. Returns a zeroed audit (null times) when no split
     * has been materialized yet.
     */
    public SplitAudit storedAudit() {
        return jdbc.query(STORED_AUDIT_SQL, AUDIT_EXTRACTOR);
    }

    private static final ResultSetExtractor<SplitAudit> AUDIT_EXTRACTOR = rs -> {
        if (!rs.next()) {
            return new SplitAudit(0, 0, 0, 0, 0, 0, null, null);
        }
        return new SplitAudit(
                rs.getInt("total"),
                rs.getInt("train_count"),
                rs.getInt("eval_count"),
                rs.getInt("group_count"),
                rs.getInt("cross_boundary"),
                rs.getInt("temporal_inversions"),
                JdbcTimestamps.instantOrNull(rs, "latest_train_time"),
                JdbcTimestamps.instantOrNull(rs, "earliest_eval_time"));
    };

    /** Per-(side, class) counts of the stored split, for surfacing class balance. */
    public Map<SplitSide, Map<GroundTruthLabel, Long>> countsBySideAndLabel() {
        Map<SplitSide, Map<GroundTruthLabel, Long>> counts = new EnumMap<>(SplitSide.class);
        RowCallbackHandler accumulate = rs -> counts
                .computeIfAbsent(SplitSide.fromDbValue(rs.getString("split_side")), k -> new EnumMap<>(GroundTruthLabel.class))
                .put(GroundTruthLabel.fromDbValue(rs.getString("label")), rs.getLong("n"));
        jdbc.query(COUNTS_BY_SIDE_AND_LABEL_SQL, accumulate);
        return counts;
    }
}
