package com.antispam.feedback;

import com.antispam.decision.Decision;
import com.antispam.experiment.ExperimentContext;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persists and reads {@code feedback_events} (story 07.02). Writes are append-only — a feedback
 * event is an immutable record of what happened in a run; nothing here updates the live decision
 * path (that gating is 07.03's job).
 */
@Repository
public class FeedbackEventRepository {

    private static final String INSERT_SQL = """
            insert into feedback_events (
                id, email_id, persona_id, run_id, action, action_confidence,
                delay_seconds, decision_shown, ground_truth, source)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_RUN_SQL = """
            select id, email_id, persona_id, run_id, action, action_confidence,
                   delay_seconds, decision_shown, ground_truth, source
            from feedback_events
            where run_id = ?
            order by email_id, persona_id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public FeedbackEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends all events of a run in one batch. */
    public void saveAll(List<FeedbackEvent> events) {
        ExperimentContext.requireLiveWritePermitted("feedback_events");
        jdbc.batchUpdate(INSERT_SQL, events, events.size(), (ps, event) -> {
            ps.setObject(1, event.id());
            ps.setObject(2, event.emailId());
            ps.setObject(3, event.personaId());
            ps.setObject(4, event.runId());
            ps.setString(5, event.action().name());
            ps.setDouble(6, event.confidence());
            ps.setLong(7, event.delaySeconds());
            ps.setString(8, event.decisionShown().name());
            ps.setString(9, event.groundTruth().dbValue());
            ps.setString(10, event.source());
        });
    }

    /** Every event of a run, ordered for stable reads. */
    public List<FeedbackEvent> findByRunId(UUID runId) {
        return jdbc.query(SELECT_BY_RUN_SQL, MAPPER, runId);
    }

    private static final RowMapper<FeedbackEvent> MAPPER = (rs, rowNum) -> new FeedbackEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("email_id", UUID.class),
            rs.getObject("persona_id", UUID.class),
            rs.getObject("run_id", UUID.class),
            FeedbackAction.valueOf(rs.getString("action")),
            rs.getDouble("action_confidence"),
            rs.getLong("delay_seconds"),
            Decision.valueOf(rs.getString("decision_shown")),
            GroundTruthLabel.fromDbValue(rs.getString("ground_truth")),
            rs.getString("source"));
}
