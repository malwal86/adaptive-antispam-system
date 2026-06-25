package com.antispam.eval;

/**
 * The whole eval-integrity story in one read (story 11.03): the time-forward leakage evidence of the
 * materialized split and the anti-circularity evidence of the judging sets. Together they back every
 * accuracy claim — the holdout is past→future (so "detects evolving spam" is honest), no family
 * straddles the split (Epic 11.01), and nothing the model helped shape grades it (feedback never
 * judges). This is the artifact a README or demo cites.
 *
 * @param timeForward the leakage-free audit of the stored split — cross-boundary families (0) and
 *                    eval-before-train overlaps (the time-forward number)
 * @param judging     the judging-source audit — high-confidence only, no feedback judging
 */
public record EvalIntegrityReport(SplitAudit timeForward, JudgingSourceAudit judging) {

    /**
     * Whether every eval-integrity guarantee holds at once: the split is leakage-free (grouped +
     * time-forward) and the judging sets are sourced cleanly (high-confidence only, no self-judging).
     */
    public boolean holds() {
        return timeForward.leakageFree() && judging.clean();
    }
}
