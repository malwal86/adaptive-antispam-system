import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { CostMeter } from "@/components/lab/story/CostMeter";
import { RouteMixBar } from "@/components/lab/story/RouteMixBar";
import { ReputationChart } from "@/components/lab/story/ReputationChart";
import { BaselineMissTable } from "@/components/lab/story/BaselineMissTable";
import { accumulateStats, EMPTY_STATS } from "@/lib/streamStats";
import type { AnalyzeResponse } from "@/lib/api";
import type { BaselineMiss } from "@/lib/arena";
import type { TrustPoint } from "@/lib/streamStats";

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

describe("CostMeter", () => {
  it("ticks up with real spend and stops at the cap", () => {
    const { rerender } = render(<CostMeter costUsd={0.25} capUsd={0.5} enforced />);
    expect(screen.getByTestId("cost-spent")).toHaveTextContent("$0.25");
    expect(screen.getByTestId("cost-meter")).not.toHaveAttribute("data-cost-at-cap");
    expect(screen.queryByTestId("cost-cap-hit")).toBeNull();

    // Real spend can't exceed the server cap; once it reaches it the meter halts.
    rerender(<CostMeter costUsd={0.5} capUsd={0.5} enforced />);
    expect(screen.getByTestId("cost-meter")).toHaveAttribute("data-cost-at-cap", "true");
    expect(screen.getByTestId("cost-cap-hit")).toBeInTheDocument();
  });

  it("flags an unenforced cap (local/dev)", () => {
    render(<CostMeter costUsd={0.1} capUsd={0.5} enforced={false} />);
    expect(screen.getByTestId("cost-unenforced")).toBeInTheDocument();
  });
});

describe("RouteMixBar", () => {
  it("renders a segment and count per route from real decisions", () => {
    const stats = accumulateStats(EMPTY_STATS, [
      decision({ routeUsed: "hard_rule" }),
      decision({ routeUsed: "model" }),
      decision({ routeUsed: "llm" }),
      decision({ routeUsed: "llm" }),
    ]);
    render(<RouteMixBar stats={stats} />);

    expect(screen.getByTestId("route-total")).toHaveTextContent("4 decided");
    expect(screen.getByTestId("route-count-rules")).toHaveTextContent("1");
    expect(screen.getByTestId("route-count-reputation")).toHaveTextContent("1");
    expect(screen.getByTestId("route-count-llm")).toHaveTextContent("2");
    expect(screen.getAllByTestId("route-segment")).toHaveLength(3);
  });

  it("draws no segments before any decision", () => {
    render(<RouteMixBar stats={EMPTY_STATS} />);
    expect(screen.queryAllByTestId("route-segment")).toHaveLength(0);
  });
});

describe("ReputationChart", () => {
  it("awaits samples until the curve can be drawn", () => {
    render(<ReputationChart series={[{ seq: 1, trust: 0.8 }]} />);
    expect(screen.getByTestId("reputation-empty")).toBeInTheDocument();
  });

  it("flags a collapse when trust falls sharply off its peak", () => {
    const series: TrustPoint[] = [
      { seq: 1, trust: 0.6 },
      { seq: 2, trust: 0.85 }, // warm-up peak
      { seq: 3, trust: 0.3 }, // attack collapse
    ];
    render(<ReputationChart series={series} />);
    expect(screen.getByTestId("reputation-chart")).toHaveAttribute("data-collapsing", "true");
    expect(screen.getByTestId("reputation-collapse")).toBeInTheDocument();
    expect(screen.getByTestId("reputation-latest")).toHaveTextContent("30%");
  });

  it("stays calm while trust holds", () => {
    const series: TrustPoint[] = [
      { seq: 1, trust: 0.7 },
      { seq: 2, trust: 0.75 },
    ];
    render(<ReputationChart series={series} />);
    expect(screen.getByTestId("reputation-chart")).not.toHaveAttribute("data-collapsing");
    expect(screen.queryByTestId("reputation-collapse")).toBeNull();
  });
});

describe("BaselineMissTable", () => {
  it("lists each baseline disagreement", () => {
    const misses: BaselineMiss[] = [
      {
        runId: "abcdef12-0000-0000-0000-000000000000",
        runAt: "2026-06-02T00:00:00Z",
        currentBypassRate: 0.1,
        baselineBypassRate: 0.6,
        delta: 0.5,
      },
    ];
    render(<BaselineMissTable misses={misses} loading={false} error={null} />);
    const rows = screen.getAllByTestId("baseline-miss-row");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("base 60%");
    expect(rows[0]).toHaveTextContent("now 10%");
    expect(rows[0]).toHaveTextContent("50%");
  });

  it("shows an empty state with no disagreements", () => {
    render(<BaselineMissTable misses={[]} loading={false} error={null} />);
    expect(screen.getByTestId("baseline-miss-empty")).toBeInTheDocument();
  });

  it("surfaces an error without blanking", () => {
    render(<BaselineMissTable misses={[]} loading={false} error="boom" />);
    expect(screen.getByTestId("baseline-miss-error")).toHaveTextContent("boom");
  });
});
