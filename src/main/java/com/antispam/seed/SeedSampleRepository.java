package com.antispam.seed;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read access to labeled seed mail for the analyzer's sample picker. Returns a
 * balanced slice — up to {@code perLabel} of each ground-truth class — so the
 * picker always offers a ham/spam/phish to try, rather than whatever happens to
 * sort first. Joins {@code ground_truth_labels} to {@code emails} for the
 * displayable subject/domain.
 */
@Repository
public class SeedSampleRepository {

    // row_number() per label gives a deterministic, balanced N-per-class slice
    // (oldest first) without pulling the whole corpus back to the application.
    private static final String SELECT_SAMPLES_SQL = """
            select email_id, label, dataset_source, subject, sender_domain
            from (
                select g.email_id        as email_id,
                       g.label           as label,
                       g.dataset_source  as dataset_source,
                       e.subject         as subject,
                       e.sender_domain   as sender_domain,
                       row_number() over (
                           partition by g.label
                           order by e.ingested_at, g.email_id) as rn
                from ground_truth_labels g
                join emails e on e.id = g.email_id
            ) ranked
            where rn <= ?
            order by label, rn
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public SeedSampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code perLabel} samples of each ground-truth class, oldest first. */
    public List<SeedSample> sample(int perLabel) {
        return jdbc.query(SELECT_SAMPLES_SQL, SAMPLE_MAPPER, perLabel);
    }

    private static final RowMapper<SeedSample> SAMPLE_MAPPER = (rs, rowNum) -> new SeedSample(
            rs.getObject("email_id", java.util.UUID.class),
            GroundTruthLabel.fromDbValue(rs.getString("label")),
            rs.getString("dataset_source"),
            rs.getString("subject"),
            rs.getString("sender_domain"));
}
