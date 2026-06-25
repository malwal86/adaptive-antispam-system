// Typed client for the adversarial arena's bypass trend (story 08.04), read by the story panel's
// "danger missed by baseline" table (story 12.04). Each run scores its abuse variants under both the
// current defender and a fixed baseline; where the baseline let more danger through than the current
// model, that gap is exactly "danger the baseline missed that the current model caught". The console
// only reads and renders these real measurements — it never re-scores anything itself.

import { API_BASE_URL, readJson } from "./api";

/** One run on the cross-run bypass trend (mirrors BypassTrendPoint.java). */
export interface BypassTrendPoint {
  runId: string;
  runAt: string;
  /** Track A bypass rate under the run's current defender in [0,1], or null if no Track A ran. */
  bypassRate: number | null;
  /** Track A bypass rate under the fixed baseline in [0,1], or null if not measured. */
  baselineBypassRate: number | null;
  /** Track B false-positive rate, or null if no Track B ran. */
  precisionFpRate: number | null;
}

/** The cross-run bypass trend (mirrors BypassTrend.java). */
export interface BypassTrend {
  points: BypassTrendPoint[];
  firstBypassRate: number | null;
  latestBypassRate: number | null;
  improved: boolean;
}

/** A run where current and baseline defenders disagreed on how much danger got through. */
export interface BaselineMiss {
  runId: string;
  runAt: string;
  currentBypassRate: number;
  baselineBypassRate: number;
  /** baseline − current: positive when the baseline missed danger the current model caught. */
  delta: number;
}

export async function fetchBypassTrend(limit = 20): Promise<BypassTrend> {
  return readJson(await fetch(`${API_BASE_URL}/arena/trend?limit=${limit}`));
}

/**
 * The runs where the baseline and current defenders measurably disagreed on danger let through —
 * the rows of the "danger missed by baseline" table. Only runs that recorded both a current and a
 * baseline bypass rate qualify (both Track A measured); newest first, and only where the gap is
 * non-trivial so the table shows signal, not rounding noise. A positive {@link BaselineMiss.delta}
 * is the headline case: the baseline missed danger the current model caught.
 */
export function baselineMisses(trend: BypassTrend, epsilon = 1e-6): BaselineMiss[] {
  return trend.points
    .filter(
      (p): p is BypassTrendPoint & { bypassRate: number; baselineBypassRate: number } =>
        p.bypassRate !== null &&
        p.baselineBypassRate !== null &&
        Math.abs(p.baselineBypassRate - p.bypassRate) > epsilon,
    )
    .map((p) => ({
      runId: p.runId,
      runAt: p.runAt,
      currentBypassRate: p.bypassRate,
      baselineBypassRate: p.baselineBypassRate,
      delta: p.baselineBypassRate - p.bypassRate,
    }))
    .reverse();
}
