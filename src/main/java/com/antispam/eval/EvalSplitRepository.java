package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final String COUNTS_BY_SIDE_SQL =
            "select split_side, count(*) as n from eval_split_assignments group by split_side";

    private static final String COUNTS_BY_SIDE_AND_LABEL_SQL = """
            select a.split_side as split_side, g.label as label, count(*) as n
            from eval_split_assignments a
            join ground_truth_labels g on g.email_id = a.email_id
            group by a.split_side, g.label
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

    /** Emails on each side of the stored split; a side with no rows is omitted. */
    public Map<SplitSide, Long> countsBySide() {
        Map<SplitSide, Long> counts = new EnumMap<>(SplitSide.class);
        RowCallbackHandler accumulate = rs ->
                counts.put(SplitSide.fromDbValue(rs.getString("split_side")), rs.getLong("n"));
        jdbc.query(COUNTS_BY_SIDE_SQL, accumulate);
        return counts;
    }

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
