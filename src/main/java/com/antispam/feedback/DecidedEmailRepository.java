package com.antispam.feedback;

import com.antispam.decision.Decision;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Loads the decision stream the feedback simulator acts on (story 07.02): labeled emails paired
 * with the latest verdict they received. A decision and a ground-truth label are both required —
 * the sampler conditions on both — so this joins {@code ground_truth_labels} to each email's most
 * recent {@code classifications} row. Ordered by {@code email_id} for a stable, reproducible stream.
 */
@Repository
public class DecidedEmailRepository {

    private static final String SELECT_STREAM_SQL = """
            select g.email_id as email_id, latest.decision as decision, g.label as label
            from ground_truth_labels g
            join lateral (
                select c.decision
                from classifications c
                where c.email_id = g.email_id
                order by c.created_at desc
                limit 1
            ) latest on true
            order by g.email_id
            limit ?
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public DecidedEmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit} labeled-and-decided emails, oldest id first. */
    public List<DecidedEmail> recentDecidedAndLabeled(int limit) {
        return jdbc.query(SELECT_STREAM_SQL, MAPPER, limit);
    }

    private static final RowMapper<DecidedEmail> MAPPER = (rs, rowNum) -> new DecidedEmail(
            rs.getObject("email_id", UUID.class),
            Decision.valueOf(rs.getString("decision")),
            GroundTruthLabel.fromDbValue(rs.getString("label")));
}
