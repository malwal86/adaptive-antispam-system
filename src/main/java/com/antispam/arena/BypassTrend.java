package com.antispam.arena;

import java.util.List;

/**
 * The cross-run bypass-rate trend (story 08.04, AC 4): the headline claim of the whole arena made
 * measurable — "the next model is harder and bypass rate drops" — as a reported series rather than a
 * hope. The {@link #points} are the most recent terminal runs in chronological order; {@link #improved}
 * is true when the latest run's bypass rate is below the earliest's, i.e. the trend is downward over the
 * window. The arena cannot <em>force</em> the drop (the defender adapts only between runs, via retrain
 * in Epic 10) — this type only measures and reports it.
 *
 * @param points           the runs on the trend, oldest first
 * @param firstBypassRate  the earliest run's bypass rate in the window, or null if none recorded one
 * @param latestBypassRate the latest run's bypass rate in the window, or null if none recorded one
 * @param improved         whether bypass rate dropped from first to latest (both non-null and latest &lt; first)
 */
public record BypassTrend(
        List<BypassTrendPoint> points,
        Double firstBypassRate,
        Double latestBypassRate,
        boolean improved) {
}
