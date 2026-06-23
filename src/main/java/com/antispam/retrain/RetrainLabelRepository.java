package com.antispam.retrain;

import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persists and reads {@code retrain_labels} (story 07.03, the label sink). Writes are append-only —
 * a label is an immutable training example; the table rejects in-place mutation. The
 * {@code provenance} payload is stored as JSONB, mirroring {@code PersonaRepository} /
 * {@code EmailFeaturesRepository}; it is handed in already-serialized so this sink stays agnostic to
 * each source's provenance shape (the feedback gate today, seed/arena in Epic 10).
 */
@Repository
public class RetrainLabelRepository {

    private static final String INSERT_SQL = """
            insert into retrain_labels (id, email_id, label, weight, source, provenance)
            values (?, ?, ?, ?, ?, ?::jsonb)
            """;

    private static final String SELECT_BY_EMAIL_SQL = """
            select id, email_id, label, weight, source, provenance
            from retrain_labels
            where email_id = ?
            order by id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public RetrainLabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends all labels in one batch. */
    public void saveAll(List<RetrainLabel> labels) {
        jdbc.batchUpdate(INSERT_SQL, labels, labels.size(), (ps, label) -> {
            ps.setObject(1, label.id());
            ps.setObject(2, label.emailId());
            ps.setString(3, label.label().dbValue());
            ps.setDouble(4, label.weight());
            ps.setString(5, label.source());
            ps.setString(6, label.provenance());
        });
    }

    /** Every label written for an email, in write order. */
    public List<RetrainLabel> findByEmailId(UUID emailId) {
        return jdbc.query(SELECT_BY_EMAIL_SQL, MAPPER, emailId);
    }

    private static final RowMapper<RetrainLabel> MAPPER = (rs, rowNum) -> new RetrainLabel(
            rs.getObject("id", UUID.class),
            rs.getObject("email_id", UUID.class),
            GroundTruthLabel.fromDbValue(rs.getString("label")),
            rs.getDouble("weight"),
            rs.getString("source"),
            rs.getString("provenance"));
}
