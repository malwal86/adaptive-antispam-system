import { describe, expect, it } from "vitest";
import type { AnalyzeResponse } from "@/lib/api";
import {
  accumulateStats,
  atCap,
  costRatio,
  EMPTY_STATS,
  formatUsd,
  MAX_TRUST_POINTS,
  routeBucket,
  routeFraction,
} from "@/lib/streamStats";

function decision(overrides: Partial<AnalyzeResponse> = {}): AnalyzeResponse {
  return {
    emailId: "e",
    classificationId: "c",
    tier: "warn",
    reasonCodes: [],
    routeUsed: "model",
    latencyMs: 5,
    explanation: "Warned.",
    decidedAt: "2026-06-05T12:00:00Z",
    duplicate: false,
    ...overrides,
  };
}

describe("routeBucket", () => {
  it("maps the pipeline's routes onto rules/reputation/LLM", () => {
    expect(routeBucket("hard_rule")).toBe("rules");
    expect(routeBucket("model")).toBe("reputation");
    expect(routeBucket("llm")).toBe("llm");
    // An unknown token falls to the content/reputation path, never crashes.
    expect(routeBucket("mystery")).toBe("reputation");
  });
});

describe("accumulateStats", () => {
  it("counts decisions per route bucket and totals them", () => {
    const stats = accumulateStats(EMPTY_STATS, [
      decision({ routeUsed: "hard_rule" }),
      decision({ routeUsed: "model" }),
      decision({ routeUsed: "llm" }),
      decision({ routeUsed: "model" }),
    ]);
    expect(stats.routeCounts).toEqual({ rules: 1, reputation: 2, llm: 1 });
    expect(stats.total).toBe(4);
    expect(routeFraction(stats, "reputation")).toBe(0.5);
  });

  it("sums real llm cost monotonically and ignores rows with none", () => {
    const first = accumulateStats(EMPTY_STATS, [
      decision({ routeUsed: "llm", llmCostUsd: 0.01 }),
      decision({ routeUsed: "model" }), // no cost
    ]);
    const second = accumulateStats(first, [decision({ routeUsed: "llm", llmCostUsd: 0.02 })]);
    expect(first.costUsd).toBeCloseTo(0.01, 10);
    expect(second.costUsd).toBeCloseTo(0.03, 10);
  });

  it("appends a trust point (1 − posterior) only for fused decisions", () => {
    const stats = accumulateStats(EMPTY_STATS, [
      decision({ posterior: 0.1 }), // trust high during warm-up
      decision({ routeUsed: "hard_rule" }), // no posterior — no trust sample
      decision({ posterior: 0.9 }), // trust collapses under attack
    ]);
    expect(stats.trustSeries.map((p) => p.seq)).toEqual([1, 2]);
    expect(stats.trustSeries[0].trust).toBeCloseTo(0.9, 10); // warm-up: trust high
    expect(stats.trustSeries[1].trust).toBeCloseTo(0.1, 10); // attack: trust collapsed
  });

  it("bounds the trust series to the most recent samples", () => {
    const many = Array.from({ length: MAX_TRUST_POINTS + 10 }, (_, i) =>
      decision({ posterior: i / 1000 }),
    );
    const stats = accumulateStats(EMPTY_STATS, many);
    expect(stats.trustSeries).toHaveLength(MAX_TRUST_POINTS);
    // The newest sample is retained; the sequence keeps climbing past the trim.
    expect(stats.trustSeries[stats.trustSeries.length - 1].seq).toBe(MAX_TRUST_POINTS + 10);
  });

  it("returns the same reference when nothing is folded", () => {
    expect(accumulateStats(EMPTY_STATS, [])).toBe(EMPTY_STATS);
  });
});

describe("cost meter math", () => {
  it("clamps the ratio to [0,1] and stops at the cap", () => {
    expect(costRatio(0.25, 0.5)).toBe(0.5);
    expect(costRatio(0.6, 0.5)).toBe(1); // real spend can't exceed the cap; the meter clamps
    expect(costRatio(0.1, 0)).toBe(0); // no meaningful cap to draw against
    expect(atCap(0.5, 0.5)).toBe(true);
    expect(atCap(0.49, 0.5)).toBe(false);
    expect(atCap(0.1, 0)).toBe(false);
  });

  it("shows sub-cent costs with enough precision to see them tick", () => {
    expect(formatUsd(0.0123)).toBe("$0.0123");
    expect(formatUsd(1.5)).toBe("$1.50");
    expect(formatUsd(0)).toBe("$0.00");
  });
});
