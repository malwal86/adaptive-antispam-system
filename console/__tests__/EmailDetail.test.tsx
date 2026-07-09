import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { EmailDetail } from "@/components/lab/EmailDetail";
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
    explanation: "Blocked on a known-bad URL.",
    decidedAt: "2026-06-05T12:00:00Z",
    duplicate: false,
    ...overrides,
  };
  return { decision, resolved, classificationIds: [decision.classificationId] };
}

describe("EmailDetail", () => {
  it("renders as a dialog with the email envelope and outcome", () => {
    render(
      <EmailDetail
        item={card({
          tier: "block",
          sender: "Your Bank",
          subject: "Verify your account now",
          preview: "Click here to confirm your details immediately.",
        })}
        onClose={vi.fn()}
      />,
    );

    const dialog = screen.getByTestId("email-detail");
    expect(dialog).toHaveAttribute("role", "dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(screen.getByTestId("detail-sender")).toHaveTextContent("Your Bank");
    expect(screen.getByTestId("detail-subject")).toHaveTextContent("Verify your account now");
    expect(screen.getByTestId("detail-preview")).toHaveTextContent("confirm your details");
    expect(screen.getByTestId("detail-outcome")).toHaveTextContent(/Moved to spam/);
  });

  it("shows the plain reason, grounded explanation, and reason-code chips", () => {
    render(<EmailDetail item={card({ sender: "Your Bank" })} onClose={vi.fn()} />);

    expect(screen.getByTestId("detail-reason")).toHaveTextContent(/known scam site/i);
    expect(screen.getByTestId("detail-explanation")).toHaveTextContent("Blocked on a known-bad URL.");
    expect(screen.getByTestId("reason-chip")).toBeInTheDocument();
  });

  it("surfaces the decision telemetry: route, latency and timing", () => {
    render(
      <EmailDetail
        item={card({ routeUsed: "hard_rule", latencyMs: 7 })}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByTestId("detail-route")).toHaveTextContent(/Hard rule/);
    expect(screen.getByTestId("detail-latency")).toHaveTextContent(/7\s*ms/);
  });

  it("shows sender trust and LLM cost when the decision carries them", () => {
    render(
      <EmailDetail
        item={card({ routeUsed: "llm", tier: "allow", reasonCodes: [], posterior: 0.2, llmCostUsd: 0.0031 })}
        onClose={vi.fn()}
      />,
    );

    // Trust is the complement of the fused posterior (0.2 -> 80%).
    expect(screen.getByTestId("detail-trust")).toHaveTextContent(/80/);
    expect(screen.getByTestId("detail-cost")).toHaveTextContent(/\$0\.0031/);
  });

  it("omits trust and cost for a hard-rule decision that carries neither", () => {
    render(<EmailDetail item={card()} onClose={vi.fn()} />);
    expect(screen.queryByTestId("detail-trust")).not.toBeInTheDocument();
    expect(screen.queryByTestId("detail-cost")).not.toBeInTheDocument();
  });

  it("reads as still checking while quarantine-pending, with no final outcome", () => {
    render(
      <EmailDetail
        item={card({ tier: "quarantine", routeUsed: "llm", reasonCodes: [], sender: "Parcel Notice" })}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId("detail-pending")).toBeInTheDocument();
    expect(screen.queryByTestId("detail-outcome")).not.toBeInTheDocument();
  });

  it("closes on the close button, the scrim, and Escape", () => {
    const onClose = vi.fn();
    render(<EmailDetail item={card({ sender: "Your Bank" })} onClose={onClose} />);

    fireEvent.click(screen.getByTestId("detail-close"));
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByTestId("detail-scrim"));
    expect(onClose).toHaveBeenCalledTimes(2);

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(3);
  });

  it("does not close when the dialog body itself is clicked", () => {
    const onClose = vi.fn();
    render(<EmailDetail item={card({ sender: "Your Bank" })} onClose={onClose} />);

    fireEvent.click(screen.getByTestId("detail-sender"));
    expect(onClose).not.toHaveBeenCalled();
  });
});
