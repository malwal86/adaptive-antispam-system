import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Analyzer } from "@/components/Analyzer";
import type { AnalyzeResponse, SeedSample } from "@/lib/api";

// Mock the API layer: the Analyzer is a thin client, so we assert it calls the
// right endpoint and renders what comes back — not any decision logic.
const analyzeRaw = vi.fn<(raw: string, source?: string) => Promise<AnalyzeResponse>>();
const analyzeById = vi.fn<(emailId: string) => Promise<AnalyzeResponse>>();
const fetchSamples = vi.fn<(perLabel?: number) => Promise<SeedSample[]>>();

vi.mock("@/lib/api", () => ({
  analyzeRaw: (raw: string, source?: string) => analyzeRaw(raw, source),
  analyzeById: (id: string) => analyzeById(id),
  fetchSamples: (n?: number) => fetchSamples(n),
}));

function decision(overrides: Partial<AnalyzeResponse> = {}): AnalyzeResponse {
  return {
    emailId: "11111111-1111-1111-1111-111111111111",
    classificationId: "22222222-2222-2222-2222-222222222222",
    tier: "block",
    reasonCodes: ["KNOWN_BAD_URL"],
    routeUsed: "hard_rule",
    latencyMs: 3,
    explanation: "Blocked: a link resolves to a known-malicious host.",
    decidedAt: "2026-06-05T12:00:00Z",
    duplicate: false,
    ...overrides,
  };
}

afterEach(() => {
  analyzeRaw.mockReset();
  analyzeById.mockReset();
  fetchSamples.mockReset();
});

describe("Analyzer", () => {
  it("shows the empty state until something is analysed", async () => {
    fetchSamples.mockResolvedValue([]);
    render(<Analyzer />);
    expect(await screen.findByTestId("empty-state")).toBeInTheDocument();
  });

  it("analyses a pasted email and renders the verdict card", async () => {
    fetchSamples.mockResolvedValue([]);
    analyzeRaw.mockResolvedValue(decision());
    const user = userEvent.setup();
    render(<Analyzer />);

    await user.type(screen.getByTestId("raw-input"), "From: x@promo.example\n\nhttp://malware.example");
    await user.click(screen.getByTestId("analyze-button"));

    expect(await screen.findByTestId("result-card")).toHaveAttribute("data-tier", "block");
    expect(analyzeRaw).toHaveBeenCalledOnce();
    expect(screen.getByTestId("tier-label")).toHaveTextContent("Spam");
  });

  it("analyses a picked seed sample by id", async () => {
    fetchSamples.mockResolvedValue([
      {
        emailId: "33333333-3333-3333-3333-333333333333",
        label: "phish",
        dataset: "phishtank",
        subject: "Verify your account",
        senderDomain: "paypa1.example",
      },
    ]);
    analyzeById.mockResolvedValue(decision({ tier: "quarantine", reasonCodes: ["MALFORMED_AUTH_BRAND_SPOOF"] }));
    const user = userEvent.setup();
    render(<Analyzer />);

    await user.click(await screen.findByTestId("sample-chip"));

    expect(await screen.findByTestId("result-card")).toHaveAttribute("data-tier", "quarantine");
    expect(analyzeById).toHaveBeenCalledWith("33333333-3333-3333-3333-333333333333");
  });

  it("surfaces an API error", async () => {
    fetchSamples.mockResolvedValue([]);
    analyzeRaw.mockRejectedValue(new Error("raw email content must not be empty"));
    const user = userEvent.setup();
    render(<Analyzer />);

    await user.type(screen.getByTestId("raw-input"), "junk");
    await user.click(screen.getByTestId("analyze-button"));

    await waitFor(() =>
      expect(screen.getByTestId("error")).toHaveTextContent("must not be empty"),
    );
  });
});
