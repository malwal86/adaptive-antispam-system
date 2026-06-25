import { describe, expect, it } from "vitest";
import { baselineMisses, type BypassTrend } from "@/lib/arena";

function trend(points: BypassTrend["points"]): BypassTrend {
  return { points, firstBypassRate: null, latestBypassRate: null, improved: false };
}

describe("baselineMisses", () => {
  it("surfaces runs where the baseline and current defenders disagreed, newest first", () => {
    const misses = baselineMisses(
      trend([
        // Oldest: baseline missed danger the current model caught (positive delta).
        { runId: "r1", runAt: "2026-06-01T00:00:00Z", bypassRate: 0.1, baselineBypassRate: 0.6, precisionFpRate: null },
        // Newest: the gap flipped — current model let more through (negative delta).
        { runId: "r2", runAt: "2026-06-02T00:00:00Z", bypassRate: 0.4, baselineBypassRate: 0.2, precisionFpRate: null },
      ]),
    );

    expect(misses.map((m) => m.runId)).toEqual(["r2", "r1"]);
    expect(misses[1].delta).toBeCloseTo(0.5, 10);
    expect(misses[0].delta).toBeCloseTo(-0.2, 10);
  });

  it("drops runs missing a measurement or with no meaningful gap", () => {
    const misses = baselineMisses(
      trend([
        { runId: "r1", runAt: "t", bypassRate: null, baselineBypassRate: 0.6, precisionFpRate: null },
        { runId: "r2", runAt: "t", bypassRate: 0.3, baselineBypassRate: null, precisionFpRate: null },
        { runId: "r3", runAt: "t", bypassRate: 0.3, baselineBypassRate: 0.3, precisionFpRate: null },
      ]),
    );
    expect(misses).toEqual([]);
  });
});
