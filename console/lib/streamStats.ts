// Live aggregate signals for the Abuse Lab story panel (story 12.04).
//
// The right rail reads the same decision feed the centre stream does, but where the centre keeps a
// bounded, newest-first list of cards, the story panel needs *monotonic* aggregates — a route mix
// that reflects the whole session, a cost meter that only ticks up, a trust curve that accumulates.
// So these stats are kept apart from the bounded card list: they fold every decision in once and
// never forget it. Each binds to a real field on the decision (no decorative-only data): the route
// split to `routeUsed`, the cost to `llmCostUsd`, the trust curve to the reputation-fused
// `posterior`. Pure functions, so they are trivially testable and deterministic.

import type { AnalyzeResponse } from "./api";

/** The story panel's route split. Maps the backend's three routes onto the PRD's rules/reputation/LLM. */
export type RouteBucket = "rules" | "reputation" | "llm";

export const ROUTE_BUCKETS: RouteBucket[] = ["rules", "reputation", "llm"];

/**
 * Maps a backend {@link AnalyzeResponse.routeUsed} token to a story-panel bucket. The PRD names the
 * split "rules/reputation/LLM"; the pipeline's routes are {@code hard_rule} (the rules),
 * {@code model} (the reputation-fused content route), and {@code llm}. An unknown token falls to the
 * model/reputation bucket — the conservative default for the content path.
 */
export function routeBucket(routeUsed: string): RouteBucket {
  if (routeUsed === "hard_rule") return "rules";
  if (routeUsed === "llm") return "llm";
  return "reputation";
}

/** One sample on the live trust curve: a monotonic sequence index and trust in [0,1]. */
export interface TrustPoint {
  seq: number;
  trust: number;
}

export interface StreamStats {
  /** Decisions counted per route bucket — the stacked bar's split. Monotonic. */
  routeCounts: Record<RouteBucket, number>;
  /** Total decisions counted (the sum of {@link routeCounts}). */
  total: number;
  /** Cumulative real LLM spend (USD) over the session — monotonic, never recomputed downward. */
  costUsd: number;
  /** Recent trust samples (1 − fused posterior), oldest→newest, bounded for the chart. */
  trustSeries: TrustPoint[];
}

/** Trust samples retained for the curve — enough to show a warm-up rise and an attack collapse. */
export const MAX_TRUST_POINTS = 60;

export const EMPTY_STATS: StreamStats = {
  routeCounts: { rules: 0, reputation: 0, llm: 0 },
  total: 0,
  costUsd: 0,
  trustSeries: [],
};

/**
 * Folds new decisions into the running stats. The caller passes only decisions not already counted
 * (deduped by classificationId), so a reconnect replay never inflates the counters — this function
 * assumes each {@code incoming} decision is novel. Monotonic by construction: route counts and cost
 * only grow, and the trust series only appends (then trims its oldest). A decision contributes to
 * the trust curve only when it carries a fused {@code posterior} (a hard-rule short-circuit has
 * none); its cost only when an {@code llm} call charged one. Pure — same inputs, same output.
 */
export function accumulateStats(prev: StreamStats, incoming: AnalyzeResponse[]): StreamStats {
  if (incoming.length === 0) {
    return prev;
  }

  const routeCounts = { ...prev.routeCounts };
  let total = prev.total;
  let costUsd = prev.costUsd;
  let trustSeries = prev.trustSeries;
  let seq = trustSeries.length > 0 ? trustSeries[trustSeries.length - 1].seq : 0;
  let seriesChanged = false;

  for (const decision of incoming) {
    routeCounts[routeBucket(decision.routeUsed)] += 1;
    total += 1;
    if (typeof decision.llmCostUsd === "number") {
      costUsd += decision.llmCostUsd;
    }
    if (typeof decision.posterior === "number") {
      if (!seriesChanged) {
        trustSeries = trustSeries.slice();
        seriesChanged = true;
      }
      seq += 1;
      trustSeries.push({ seq, trust: clamp01(1 - decision.posterior) });
    }
  }

  if (seriesChanged && trustSeries.length > MAX_TRUST_POINTS) {
    trustSeries = trustSeries.slice(trustSeries.length - MAX_TRUST_POINTS);
  }

  return { routeCounts, total, costUsd, trustSeries };
}

/** A route bucket's share of all decisions in [0,1]; 0 before any decision has landed. */
export function routeFraction(stats: StreamStats, bucket: RouteBucket): number {
  return stats.total > 0 ? stats.routeCounts[bucket] / stats.total : 0;
}

/**
 * Cumulative spend as a fraction of the cap in [0,1], clamped so the meter can never overrun its
 * track. Returns 0 when the cap is non-positive (no meaningful bound to draw against).
 */
export function costRatio(costUsd: number, capUsd: number): number {
  if (!(capUsd > 0)) {
    return 0;
  }
  return clamp01(costUsd / capUsd);
}

/**
 * Whether cumulative spend has reached the cap — the point at which the meter visibly stops. Real
 * spend can't exceed the server-enforced cap (story 05.04), so the meter plateaus here rather than
 * being faked to stop.
 */
export function atCap(costUsd: number, capUsd: number): boolean {
  return capUsd > 0 && costUsd >= capUsd - 1e-9;
}

/** Formats a USD amount with enough precision to show sub-cent LLM costs ticking up. */
export function formatUsd(value: number): string {
  const fractionDigits = value !== 0 && Math.abs(value) < 0.1 ? 4 : 2;
  return `$${value.toFixed(fractionDigits)}`;
}

function clamp01(value: number): number {
  if (Number.isNaN(value)) {
    return 0;
  }
  return value < 0 ? 0 : value > 1 ? 1 : value;
}
