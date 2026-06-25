package com.antispam.eval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * Audits the source of every judging label (story 11.03). It answers one question against the live
 * database: is any email the system judges against graded by simulator feedback rather than a
 * high-confidence ground-truth label? The judging sets are the eval side of the split, the frozen
 * golden set, and the rolling fresh set; the forbidden source is a feedback {@code retrain_label}
 * (the corroboration gate's sink, story 07.03) on an email with no ground truth. Both counts are
 * expected to be zero — this repository exists to prove it from the data, not assume it.
 */
@Repository
public class EvalIntegrityRepository {

    // One pass over the union of judging emails, left-joined to ground truth and to feedback-sourced
    // labels. without_ground_truth: a judging email with no high-confidence label. feedback_only: a
    // judging email that has a feedback label and no ground truth — the circular-validation case the
    // rule forbids. feedback_labeled_total: how many feedback labels exist at all, for context.
    private static final String JUDGING_SOURCE_AUDIT_SQL = """
            with judging as (
                select email_id from eval_split_assignments where split_side = 'eval'
                union
                select email_id from golden_set_members
                union
                select email_id from fresh_challenge_members
            ),
            feedback as (
                select distinct email_id from retrain_labels where source = 'feedback'
            )
            select count(*)                                                          as judging_total,
                   count(*) filter (where gt.email_id is null)                       as without_ground_truth,
                   count(*) filter (where fb.email_id is not null
                                      and gt.email_id is null)                       as feedback_only_in_judging,
                   (select count(*) from feedback)                                   as feedback_labeled_total
            from judging j
            left join ground_truth_labels gt on gt.email_id = j.email_id
            left join feedback fb on fb.email_id = j.email_id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public EvalIntegrityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts the judging-set emails graded without ground truth, and those graded only by feedback. */
    public JudgingSourceAudit auditJudgingSources() {
        return jdbc.query(JUDGING_SOURCE_AUDIT_SQL, AUDIT_EXTRACTOR);
    }

    private static final ResultSetExtractor<JudgingSourceAudit> AUDIT_EXTRACTOR = rs -> {
        if (!rs.next()) {
            return new JudgingSourceAudit(0, 0, 0, 0);
        }
        return new JudgingSourceAudit(
                rs.getLong("judging_total"),
                rs.getLong("without_ground_truth"),
                rs.getLong("feedback_only_in_judging"),
                rs.getLong("feedback_labeled_total"));
    };
}
