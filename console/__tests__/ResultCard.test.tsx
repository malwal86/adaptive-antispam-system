import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ResultCard } from "@/components/ResultCard";
import type { AnalyzeResponse, Tier } from "@/lib/api";
import { TIERS } from "@/lib/tiers";

function fixture(overrides: Partial<AnalyzeResponse> = {}): AnalyzeResponse {
  return {
    emailId: "d7db7c1d-59aa-4e88-83a3-895c13b39907",
    classificationId: "b5c0b6eb-64d5-4932-97a0-33fd65576adf",
    tier: "block",
    reasonCodes: ["KNOWN_BAD_URL"],
    routeUsed: "hard_rule",
    latencyMs: 2,
    explanation: "Blocked: a link resolves to a known-malicious host.",
    decidedAt: "2026-06-05T12:00:00Z",
    duplicate: false,
    ...overrides,
  };
}

describe("ResultCard", () => {
  const tiers: Tier[] = ["allow", "warn", "quarantine", "block"];

  it.each(tiers)("renders the %s tier distinctly from a fixture", (tier) => {
    render(<ResultCard result={fixture({ tier, reasonCodes: [] })} />);

    const card = screen.getByTestId("result-card");
    expect(card).toHaveAttribute("data-tier", tier);
    expect(screen.getByTestId("tier-label")).toHaveTextContent(TIERS[tier].label);
  });

  it("shows reason chips, route, latency and the explanation", () => {
    render(
      <ResultCard
        result={fixture({
          reasonCodes: ["KNOWN_BAD_URL", "MALFORMED_AUTH_BRAND_SPOOF"],
          latencyMs: 7,
        })}
      />,
    );

    expect(screen.getAllByTestId("reason-chip")).toHaveLength(2);
    expect(screen.getByTestId("route-used")).toHaveTextContent("Hard rule");
    expect(screen.getByTestId("latency")).toHaveTextContent("7 ms");
    expect(screen.getByTestId("explanation")).toHaveTextContent("known-malicious host");
  });

  it("renders a clean allow with no reason chips", () => {
    render(
      <ResultCard
        result={fixture({
          tier: "allow",
          reasonCodes: [],
          routeUsed: "model",
          explanation: "No hard-rule or model signal fired; provisionally allowed.",
        })}
      />,
    );

    expect(screen.queryByTestId("reason-chip")).not.toBeInTheDocument();
    expect(screen.getByTestId("route-used")).toHaveTextContent("Model");
  });
});
