package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The eval-integrity report against a real Postgres (story 11.03). It proves the two finalizing
 * guarantees: the materialized split's leakage evidence is readable without a recompute, and the
 * judging-source audit has teeth — it stays at zero while feedback merely trains, and it catches a
 * feedback-only label the moment one is smuggled into a judging set.
 *
 * <p>The suite shares one global eval state, so the anti-circularity assertions are made as deltas
 * around a single forced violation (insert → detect → remove → clear), which is robust to whatever
 * else the corpus holds.
 */
class EvalIntegrityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private BootstrapEvalSplitService splitService;

    @Autowired
    private EvalSetService evalSets;

    @Autowired
    private EvalIntegrityService integrity;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID ingest(String tag) {
        IngestResult ingested = ingestService.ingest(
                ("From: " + tag + "@integrity.test\nSubject: hi\n\n" + tag + " " + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8), "api");
        return ingested.emailId();
    }

    private UUID ingestLabeledEval(String tag, GroundTruthLabel label) {
        UUID emailId = ingest(tag);
        jdbc.update("insert into ground_truth_labels (email_id, label, dataset_source) "
                + "values (?, ?, 'test') on conflict (email_id) do nothing", emailId, label.dbValue());
        jdbc.update("""
                insert into eval_split_assignments (email_id, split_side, group_key)
                values (?, 'eval', ?)
                on conflict (email_id) do update set split_side = 'eval'
                """, emailId, tag + "-" + UUID.randomUUID());
        return emailId;
    }

    private void insertFeedbackLabel(UUID emailId) {
        jdbc.update("""
                insert into retrain_labels (id, email_id, label, weight, source, provenance)
                values (?, ?, 'spam', 1.0, 'feedback', '{}'::jsonb)
                """, UUID.randomUUID(), emailId);
    }

    @Test
    void the_report_is_readable_and_the_judging_sets_are_high_confidence_with_feedback_only_training() {
        // A materialized, grouped split: the time-forward audit must read back leakage-free grouping.
        UUID judged = ingestLabeledEval("integ-eval", GroundTruthLabel.SPAM);
        splitService.rebuild();
        evalSets.freezeGolden("integrity-golden-" + UUID.randomUUID());

        // Feedback exists and trains — a feedback label on a ground-truth email — but must never judge.
        insertFeedbackLabel(judged);

        EvalIntegrityReport report = integrity.report();

        // No family straddles the split (the splitter's hard guarantee, re-derived from the table).
        assertThat(report.timeForward().crossBoundaryGroups()).isZero();
        assertThat(report.timeForward().total()).isPositive();
        assertThat(report.timeForward().latestTrainTime()).isNotNull();
        assertThat(report.timeForward().earliestEvalTime()).isNotNull();

        // Judging is high-confidence only and never graded by feedback, even though feedback exists.
        assertThat(report.judging().feedbackLabeledTotal()).isPositive();
        assertThat(report.judging().judgingWithoutGroundTruth()).isZero();
        assertThat(report.judging().feedbackDerivedInJudging()).isZero();
        assertThat(report.judging().clean()).isTrue();
    }

    @Test
    void the_audit_catches_a_feedback_only_label_smuggled_into_a_judging_set() {
        // An email known ONLY through feedback — no ground truth. It trains, but must never judge.
        UUID feedbackOnly = ingest("integ-feedback-only");
        insertFeedbackLabel(feedbackOnly);

        long baseline = integrity.report().judging().feedbackDerivedInJudging();

        // Smuggle it into a judging set (the fresh set, which carries no immutability trigger).
        jdbc.update("insert into fresh_challenge_members (email_id, label, source) "
                + "values (?, 'spam', 'leak') on conflict (email_id) do nothing", feedbackOnly);
        long withLeak = integrity.report().judging().feedbackDerivedInJudging();

        // The audit flags exactly the smuggled label — circular validation is detected, not hidden.
        assertThat(withLeak).isEqualTo(baseline + 1);

        // Remove the leak; the count returns to baseline (the retrain label stays — it only trains).
        jdbc.update("delete from fresh_challenge_members where email_id = ?", feedbackOnly);
        assertThat(integrity.report().judging().feedbackDerivedInJudging()).isEqualTo(baseline);
    }
}
