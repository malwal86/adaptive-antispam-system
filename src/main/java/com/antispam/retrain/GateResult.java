package com.antispam.retrain;

/**
 * The promotion gate's verdict on one retrain candidate (story 10.03): whether it cleared the
 * precision floor on the frozen golden set, the precision it earned against the floor it had to beat,
 * and the {@link GateEvidence} (recall/bypass/cost/latency) reported alongside. This is the object
 * that decides whether 10.04 may flip the candidate live — a {@code false} {@link #passed()} means the
 * candidate is <b>not</b> promoted, no matter how good its other metrics are.
 *
 * <p>It is a pure function of the candidate's scorecard and the configured floor, so re-running the
 * gate on the same golden replay yields an equal result (AC 5).
 *
 * @param passed           {@code true} iff the candidate cleared the precision floor on a large-enough
 *                         golden set — the single bit 10.04 acts on
 * @param precision        the candidate's measured precision on the golden set
 * @param precisionFloor   the fixed floor it had to reach (inclusive)
 * @param goldenSampleCount the number of golden-set emails graded (the measurement's base)
 * @param policyVersion    the candidate policy the golden set was replayed under
 * @param modelVersion     the model artifact that policy is calibrated for (the thing being promoted)
 * @param evidence         the reported, non-blocking metrics
 * @param reason           a human-readable explanation of the verdict (which condition decided it)
 */
public record GateResult(
        boolean passed,
        double precision,
        double precisionFloor,
        long goldenSampleCount,
        String policyVersion,
        String modelVersion,
        GateEvidence evidence,
        String reason) {
}
