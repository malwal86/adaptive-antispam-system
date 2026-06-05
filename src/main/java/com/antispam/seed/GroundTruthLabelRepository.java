package com.antispam.seed;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code ground_truth_labels} table. Writes are idempotent on
 * {@code email_id} ({@link #saveIfAbsent}) so re-seeding never creates a second
 * label for the same email, which — together with 01.02's content-hash dedupe —
 * is what makes the whole seed operation idempotent.
 */
@Repository
public class GroundTruthLabelRepository {

    private static final String INSERT_SQL = """
            insert into ground_truth_labels (email_id, label, dataset_source)
            values (?, ?, ?)
            on conflict (email_id) do nothing
            """;

    private static final String SELECT_LABEL_SQL =
            "select label from ground_truth_labels where email_id = ?";

    private static final String COUNT_BY_LABEL_SQL =
            "select label, count(*) as n from ground_truth_labels group by label";

    private final JdbcTemplate jdbc;

    @Autowired
    public GroundTruthLabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records {@code label} for {@code emailId} unless a label already exists.
     *
     * @return true if a new label row was written; false if one was already present
     */
    public boolean saveIfAbsent(UUID emailId, GroundTruthLabel label, String datasetSource) {
        return jdbc.update(INSERT_SQL, emailId, label.dbValue(), datasetSource) > 0;
    }

    public Optional<GroundTruthLabel> findByEmailId(UUID emailId) {
        try {
            String value = jdbc.queryForObject(SELECT_LABEL_SQL, String.class, emailId);
            return Optional.of(GroundTruthLabel.fromDbValue(value));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Per-class counts across the whole corpus; classes with no rows are omitted. */
    public Map<GroundTruthLabel, Long> countsByLabel() {
        Map<GroundTruthLabel, Long> counts = new EnumMap<>(GroundTruthLabel.class);
        RowCallbackHandler accumulate = rs ->
                counts.put(GroundTruthLabel.fromDbValue(rs.getString("label")), rs.getLong("n"));
        jdbc.query(COUNT_BY_LABEL_SQL, accumulate);
        return counts;
    }
}
