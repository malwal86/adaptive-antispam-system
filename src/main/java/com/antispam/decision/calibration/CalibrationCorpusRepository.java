package com.antispam.decision.calibration;

import com.antispam.eval.SplitSide;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads the labeled corpus already partitioned by the leakage-free train/eval split
 * (stories 11.01 / 11.03) into the points calibration needs: each email's split side and
 * whether it is abuse. Calibration fits its calibrator on the {@code train} side and
 * measures reliability on the held-out {@code eval} side — exactly the partition the split
 * table was built to guarantee ("calibration draws its held-out reliability set from
 * {@code split_side = 'eval'}", V10), so no family that the model could have memorised
 * straddles the boundary.
 *
 * <p>The class label collapses to the binary event the model's confidence predicts:
 * {@code positive = the mail is abuse (spam or phish), not ham}.
 */
@Repository
public class CalibrationCorpusRepository {

    /**
     * One labeled, split-assigned email for calibration.
     *
     * @param emailId  the email to score
     * @param side     which side of the split it sits on
     * @param positive whether it is abuse (spam or phish) rather than ham
     */
    public record LabeledSplitRow(UUID emailId, SplitSide side, boolean positive) {
    }

    private static final String SELECT_SQL = """
            select j.email_id   as email_id,
                   j.split_side as split_side,
                   g.label      as label
            from eval_split_assignments j
            join ground_truth_labels g on g.email_id = j.email_id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public CalibrationCorpusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every split-assigned labeled email, as a calibration point. */
    public List<LabeledSplitRow> loadLabeledSplit() {
        return jdbc.query(SELECT_SQL, ROW_MAPPER);
    }

    private static final RowMapper<LabeledSplitRow> ROW_MAPPER = (rs, rowNum) -> new LabeledSplitRow(
            rs.getObject("email_id", UUID.class),
            SplitSide.fromDbValue(rs.getString("split_side")),
            GroundTruthLabel.fromDbValue(rs.getString("label")) != GroundTruthLabel.HAM);
}
