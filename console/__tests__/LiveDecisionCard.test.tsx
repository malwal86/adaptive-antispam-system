import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { LiveDecisionCard } from "@/components/lab/LiveDecisionCard";
import type { AnalyzeResponse } from "@/lib/api";
import type { StreamItem } from "@/lib/decisionStream";

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
  it("reads like an email: from, subject, and a one-line preview", () => {
    render(
      <LiveDecisionCard
        item={card({
          tier: "allow",
          routeUsed: "model",
          reasonCodes: [],
          sender: "Mom",
          subject: "Dinner Sunday?",
          preview: "Are you free for dinner this Sunday?",
        }, true)}
      />,
    );
    expect(screen.getByTestId("card-sender")).toHaveTextContent("Mom");
    expect(screen.getByTestId("card-subject")).toHaveTextContent("Dinner Sunday?");
    expect(screen.getByTestId("card-preview")).toHaveTextContent("free for dinner");
  });

  it("frames a good email as delivered to the inbox", () => {
    render(
      <LiveDecisionCard
        item={card({ tier: "allow", routeUsed: "model", reasonCodes: [], sender: "Acme Store" }, true)}
      />,
    );
    const el = screen.getByTestId("live-decision-card");
    expect(el).toHaveAttribute("data-folder", "inbox");
    expect(screen.getByTestId("card-outcome")).toHaveTextContent(/Delivered to inbox/);
  });

  it("frames a scam as moved to spam, in plain language", () => {
    render(
      <LiveDecisionCard
        item={card({ tier: "block", routeUsed: "hard_rule", reasonCodes: ["KNOWN_BAD_URL"], sender: "Your Bank" })}
      />,
    );
    const el = screen.getByTestId("live-decision-card");
    expect(el).toHaveAttribute("data-folder", "spam");
    expect(screen.getByTestId("card-outcome")).toHaveTextContent(/Moved to spam/);
    // Plain, non-technical reason — not the raw reason code.
    expect(screen.getByTestId("card-reason")).toHaveTextContent(/known scam site/i);
    expect(screen.getByTestId("card-reason")).not.toHaveTextContent(/KNOWN_BAD_URL/);
  });

  it("shows a 'Checking…' affordance while quarantine-pending, no outcome yet", () => {
    render(
      <LiveDecisionCard
        item={card({ tier: "quarantine", routeUsed: "llm", reasonCodes: [], sender: "Parcel Notice" })}
      />,
    );
    expect(screen.getByTestId("live-decision-card")).toHaveAttribute("data-pending", "true");
    expect(screen.getByTestId("pending-indicator")).toHaveTextContent(/Checking/);
    expect(screen.queryByTestId("card-outcome")).not.toBeInTheDocument();
  });

  it("flips from checking to the final outcome once resolved (never a retraction)", () => {
    const { rerender } = render(
      <LiveDecisionCard item={card({ tier: "quarantine", routeUsed: "llm", reasonCodes: [], sender: "Parcel Notice" })} />,
    );
    expect(screen.getByTestId("pending-indicator")).toBeInTheDocument();

    rerender(
      <LiveDecisionCard
        item={card({ tier: "block", routeUsed: "llm", reasonCodes: [], sender: "Parcel Notice" }, true)}
      />,
    );
    const el = screen.getByTestId("live-decision-card");
    expect(el).toHaveAttribute("data-resolved", "true");
    expect(el).not.toHaveAttribute("data-pending");
    expect(screen.queryByTestId("pending-indicator")).not.toBeInTheDocument();
    expect(screen.getByTestId("card-outcome")).toHaveTextContent(/Moved to spam/);
  });

  it("falls back to a short id when the feed carries no envelope (ordinary traffic)", () => {
    render(<LiveDecisionCard item={card({ tier: "allow", routeUsed: "model", reasonCodes: [] }, true)} />);
    expect(screen.getByTestId("card-sender")).toHaveTextContent(/d7db7c1d/i);
    expect(screen.queryByTestId("card-subject")).not.toBeInTheDocument();
  });

  it("is a button that reports its selection when onSelect is given", () => {
    const onSelect = vi.fn();
    render(
      <LiveDecisionCard
        item={card({ tier: "allow", routeUsed: "model", reasonCodes: [], sender: "Mom" }, true)}
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
        item={card({ tier: "allow", routeUsed: "model", reasonCodes: [], sender: "Mom" }, true)}
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
