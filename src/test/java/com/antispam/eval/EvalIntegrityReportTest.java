package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The derived verdicts of the eval-integrity records (story 11.03), as pure units: a judging-source
 * audit is clean only when nothing is graded without ground truth and nothing is graded by feedback,
 * and the combined report holds only when the split is also leakage-free. These booleans are what an
 * endpoint and the demo turn into a single "eval integrity holds" claim, so their exact thresholds are
 * pinned here.
 */
class EvalIntegrityReportTest {

    private static SplitAudit leakageFreeSplit() {
        // 0 cross-boundary families and 0 temporal inversions → leakage-free.
        return new SplitAudit(100, 80, 20, 30, 0, 0, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
    }

    @Test
    void a_judging_audit_is_clean_only_with_no_feedback_and_full_ground_truth() {
        assertThat(new JudgingSourceAudit(20, 0, 0, 5).clean()).isTrue();
        // A feedback-only label in a judging set breaks anti-circularity.
        assertThat(new JudgingSourceAudit(20, 1, 1, 5).noSelfJudge()).isFalse();
        assertThat(new JudgingSourceAudit(20, 1, 1, 5).clean()).isFalse();
        // A judging email with no ground truth (even if not feedback) is still not high-confidence.
        assertThat(new JudgingSourceAudit(20, 1, 0, 5).allHighConfidence()).isFalse();
        assertThat(new JudgingSourceAudit(20, 1, 0, 5).clean()).isFalse();
    }

    @Test
    void feedback_labels_existing_at_all_does_not_break_the_audit() {
        // Feedback may train: 5 emails carry feedback labels, but none judges → still clean.
        assertThat(new JudgingSourceAudit(20, 0, 0, 5).clean()).isTrue();
    }

    @Test
    void the_report_holds_only_when_split_and_judging_are_both_sound() {
        JudgingSourceAudit cleanJudging = new JudgingSourceAudit(20, 0, 0, 5);
        assertThat(new EvalIntegrityReport(leakageFreeSplit(), cleanJudging).holds()).isTrue();

        // Leaky split, clean judging → does not hold.
        SplitAudit leakySplit = new SplitAudit(100, 80, 20, 30, 1, 2, Instant.EPOCH, Instant.EPOCH);
        assertThat(new EvalIntegrityReport(leakySplit, cleanJudging).holds()).isFalse();

        // Clean split, self-judging → does not hold.
        JudgingSourceAudit selfJudging = new JudgingSourceAudit(20, 1, 1, 5);
        assertThat(new EvalIntegrityReport(leakageFreeSplit(), selfJudging).holds()).isFalse();
    }
}
