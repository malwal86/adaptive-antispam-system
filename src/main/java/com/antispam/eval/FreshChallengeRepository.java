package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The rolling fresh challenge set in {@code fresh_challenge_members} (story 11.02): the latest reported
 * attacks, kept deliberately separate from the frozen golden benchmark so it can grow without moving
 * the gate's comparable baseline. This repository only ever appends — there is no path here that
 * touches the golden tables — which is the structural guarantee behind "fresh attacks never enter the
 * frozen golden set".
 */
@Repository
public class FreshChallengeRepository {

    // Idempotent append: the fresh set is a set, so re-reporting an email refreshes nothing and never
    // errors. It writes only this table — never golden — by construction.
    private static final String ADD_SQL = """
            insert into fresh_challenge_members (email_id, label, source)
            values (?, ?, ?)
            on conflict (email_id) do nothing
            """;

    private static final String COUNTS_BY_LABEL_SQL = """
            select label, count(*) as n
            from fresh_challenge_members
            group by label
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public FreshChallengeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends one reported attack to the fresh set, idempotently.
     *
     * @param emailId the reported email
     * @param label   its high-confidence class
     * @param source  where the report came from (audit)
     */
    public void add(UUID emailId, GroundTruthLabel label, String source) {
        jdbc.update(ADD_SQL, emailId, label.dbValue(), source);
    }

    /** Per-class counts of the fresh set, for surfacing its size in a report. */
    public Map<GroundTruthLabel, Long> countsByLabel() {
        Map<GroundTruthLabel, Long> counts = new EnumMap<>(GroundTruthLabel.class);
        jdbc.query(COUNTS_BY_LABEL_SQL, rs -> {
            counts.put(GroundTruthLabel.fromDbValue(rs.getString("label")), rs.getLong("n"));
        });
        return counts;
    }
}
