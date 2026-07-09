import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { LiveDecisionCard } from "@/components/lab/LiveDecisionCard";
import type { AnalyzeResponse, Tier } from "@/lib/api";
import type { StreamItem } from "@/lib/decisionStream";
import { TIERS } from "@/lib/tiers";

function card(overrides: Partial<AnalyzeResponse> = {}, resolved = false): StreamItem {
  const decision: AnalyzeResponse = {
    emailId: "d7db7c1d-59aa-4e88-83a3-895c13b39907",
    classificationId: "c1",
    tier: "block",
    reasonCodes: ["KNOWN_BAD_URL"],
    routeUsed: "hard_rule",
    latencyMs: 2,
    explanation: "Blocked.",
    decidedAt: "2026-06-05T12:00:00Z",
    duplicate: false,
    ...overrides,
  };
  return { decision, resolved, classificationIds: [decision.classificationId] };
}

describe("LiveDecisionCard", () => {
  it("renders each of the four tiers, distinct and labelled per the analyzer vocabulary", () => {
    (["allow", "warn", "quarantine", "block"] as Tier[]).forEach((tier) => {
      const { unmount } = render(
        // route=model keeps a quarantine final (not pending) for this tier-vocabulary check.
        <LiveDecisionCard item={card({ tier, routeUsed: "model", reasonCodes: [] }, true)} />,
      );
      expect(screen.getByTestId("live-decision-card")).toHaveAttribute("data-tier", tier);
      expect(screen.getByTestId("tier-label")).toHaveTextContent(TIERS[tier].label);
      unmount();
    });
  });

  it("shows the resolving affordance while quarantine-pending, with no reason chips", () => {
    render(<LiveDecisionCard item={card({ tier: "quarantine", routeUsed: "llm", reasonCodes: [] })} />);
    expect(screen.getByTestId("live-decision-card")).toHaveAttribute("data-pending", "true");
    expect(screen.getByTestId("pending-indicator")).toHaveTextContent(/Resolving/);
    expect(screen.queryByTestId("reason-chip")).not.toBeInTheDocument();
  });

  it("drops the pending affordance once resolved and shows the final tier", () => {
    render(
      <LiveDecisionCard
        item={card({ tier: "allow", routeUsed: "llm", reasonCodes: [] }, true)}
      />,
    );
    const el = screen.getByTestId("live-decision-card");
    expect(el).toHaveAttribute("data-tier", "allow");
    expect(el).toHaveAttribute("data-resolved", "true");
    expect(el).not.toHaveAttribute("data-pending");
    expect(screen.queryByTestId("pending-indicator")).not.toBeInTheDocument();
  });

  it("reflects the deciding route and its reason chips", () => {
    const { rerender } = render(<LiveDecisionCard item={card({ routeUsed: "hard_rule" })} />);
    expect(screen.getByTestId("route-used")).toHaveTextContent("Hard rule");
    expect(screen.getByTestId("reason-chip")).toHaveTextContent("Known-bad URL");

    rerender(<LiveDecisionCard item={card({ routeUsed: "model", reasonCodes: [] })} />);
    expect(screen.getByTestId("route-used")).toHaveTextContent("Model");

    rerender(<LiveDecisionCard item={card({ routeUsed: "llm", tier: "allow", reasonCodes: [] }, true)} />);
    expect(screen.getByTestId("route-used")).toHaveTextContent("LLM");
  });

  it("is a button that reports its selection when onSelect is given", () => {
    const onSelect = vi.fn();
    render(
      <LiveDecisionCard
        item={card({ tier: "allow", routeUsed: "model", reasonCodes: [] }, true)}
        onSelect={onSelect}
      />,
    );
    const el = screen.getByTestId("live-decision-card");
    expect(el).toHaveAttribute("role", "button");
    fireEvent.click(el);
    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  it("opens on keyboard activation (Enter / Space)", () => {
    const onSelect = vi.fn();
    render(
      <LiveDecisionCard
        item={card({ tier: "allow", routeUsed: "model", reasonCodes: [] }, true)}
        onSelect={onSelect}
      />,
    );
    const el = screen.getByTestId("live-decision-card");
    fireEvent.keyDown(el, { key: "Enter" });
    fireEvent.keyDown(el, { key: " " });
    expect(onSelect).toHaveBeenCalledTimes(2);
  });

  it("is inert (no button role) when no onSelect is provided", () => {
    render(<LiveDecisionCard item={card({ tier: "allow", routeUsed: "model", reasonCodes: [] }, true)} />);
    expect(screen.getByTestId("live-decision-card")).not.toHaveAttribute("role", "button");
  });
});
