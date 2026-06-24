package com.antispam.arena;

import java.time.Instant;
import java.util.UUID;

/**
 * One run's place on the cross-run bypass-rate trend (story 08.04, AC 4): its achieved Track A bypass
 * rate set against the same fixed baseline, so successive runs are comparable as the defender retrains
 * between them (Epic 10). The {@code baselineBypassRate} is the stable anchor — the rate the genesis
 * (or configured) defender would have allowed on this run's variants — while {@code bypassRate} is what
 * the current defender allowed; the precision dimension rides along so a reader can see recall hardening
 * without silently costing precision.
 *
 * @param runId              the run this point summarizes
 * @param runAt              when the run started — the trend's x-axis
 * @param bypassRate         the Track A bypass rate under the run's current defender, or null if no Track A
 * @param baselineBypassRate the Track A bypass rate under the fixed baseline, or null if not measured / no Track A
 * @param precisionFpRate    the Track B false-positive rate, or null if no Track B ran
 */
public record BypassTrendPoint(
        UUID runId,
        Instant runAt,
        Double bypassRate,
        Double baselineBypassRate,
        Double precisionFpRate) {
}
