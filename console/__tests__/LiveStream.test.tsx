import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { LiveStream } from "@/components/lab/LiveStream";
import type { AnalyzeResponse } from "@/lib/api";
import type { StreamItem } from "@/lib/decisionStream";

function card(overrides: Partial<AnalyzeResponse> = {}, resolved = false): StreamItem {
  const decision: AnalyzeResponse = {
    emailId: "d7db7c1d-59aa-4e88-83a3-895c13b39907",
    classificationId: "b5c0b6eb-64d5-4932-97a0-33fd65576adf",
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

describe("LiveStream", () => {
  it("shows the waiting empty state with no decisions", () => {
    render(<LiveStream items={[]} status="open" />);
    expect(screen.getByTestId("stream-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("live-decision-card")).not.toBeInTheDocument();
  });

  it("reflects the connection status", () => {
    render(<LiveStream items={[]} status="reconnecting" />);
    expect(screen.getByTestId("stream-status")).toHaveAttribute("data-status", "reconnecting");
  });

  it("renders a card per email, tier-coded, newest-first", () => {
    render(
      <LiveStream
        status="open"
        items={[
          card({ emailId: "e-block", classificationId: "b", tier: "block" }),
          card({ emailId: "e-allow", classificationId: "a", tier: "allow", reasonCodes: [], routeUsed: "model" }),
        ]}
      />,
    );

    const cards = screen.getAllByTestId("live-decision-card");
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveAttribute("data-tier", "block");
    expect(cards[1]).toHaveAttribute("data-tier", "allow");
    // The scam's plain-language reason, not the raw reason code.
    expect(screen.getByText(/known scam site/i)).toBeInTheDocument();
  });

  it("tallies where mail landed at a glance", () => {
    render(
      <LiveStream
        status="open"
        items={[
          card({ emailId: "e-block", classificationId: "b", tier: "block" }),
          card({ emailId: "e-allow", classificationId: "a", tier: "allow", reasonCodes: [], routeUsed: "model" }, true),
          card({ emailId: "e-pending", classificationId: "p", tier: "quarantine", reasonCodes: [], routeUsed: "llm" }),
        ]}
      />,
    );
    const summary = screen.getByTestId("stream-tally");
    expect(summary).toHaveTextContent(/1 delivered/);
    expect(summary).toHaveTextContent(/1 blocked/);
    expect(summary).toHaveTextContent(/1 checking/);
  });

  it("opens a detail dialog for the clicked email, then closes it", () => {
    render(
      <LiveStream
        status="open"
        items={[
          card({
            emailId: "e-block",
            classificationId: "b",
            tier: "block",
            sender: "Your Bank",
            subject: "Verify your account now",
          }),
        ]}
      />,
    );

    expect(screen.queryByTestId("email-detail")).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId("live-decision-card"));

    const dialog = screen.getByTestId("email-detail");
    expect(dialog).toBeInTheDocument();
    expect(screen.getByTestId("detail-subject")).toHaveTextContent("Verify your account now");

    fireEvent.click(screen.getByTestId("detail-close"));
    expect(screen.queryByTestId("email-detail")).not.toBeInTheDocument();
  });

  it("keeps the open dialog bound to the live email as it resolves in place", () => {
    const { rerender } = render(
      <LiveStream
        status="open"
        items={[
          card({ emailId: "e1", classificationId: "p", tier: "quarantine", reasonCodes: [], routeUsed: "llm", sender: "Parcel Notice" }),
        ]}
      />,
    );
    fireEvent.click(screen.getByTestId("live-decision-card"));
    expect(screen.getByTestId("detail-pending")).toBeInTheDocument();

    // The same email resolves to a final tier — the dialog reflects it without reopening.
    rerender(
      <LiveStream
        status="open"
        items={[
          card({ emailId: "e1", classificationId: "r", tier: "block", reasonCodes: ["KNOWN_BAD_URL"], routeUsed: "llm", sender: "Parcel Notice" }, true),
        ]}
      />,
    );
    expect(screen.queryByTestId("detail-pending")).not.toBeInTheDocument();
    expect(screen.getByTestId("detail-outcome")).toHaveTextContent(/Moved to spam/);
  });
});
