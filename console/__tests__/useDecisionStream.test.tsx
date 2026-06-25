import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useDecisionStream } from "@/lib/useDecisionStream";
import type { AnalyzeResponse } from "@/lib/api";

function decision(id: string, overrides: Partial<AnalyzeResponse> = {}): AnalyzeResponse {
  return {
    emailId: `email-${id}`,
    classificationId: id,
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

class FakeEventSource {
  readonly CLOSED = 2;
  readyState = 0;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, (e: MessageEvent) => void>();
  constructor(public url: string) {}
  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    this.listeners.set(type, cb);
  }
  close = vi.fn();
  emit(d: unknown) {
    this.listeners.get("decision")?.({ data: JSON.stringify(d) } as MessageEvent);
  }
}

describe("useDecisionStream", () => {
  it("exposes live decisions newest-first and coalesces a burst", async () => {
    let source!: FakeEventSource;
    const factory = (url: string) => (source = new FakeEventSource(url)) as unknown as EventSource;

    const { result } = renderHook(() =>
      useDecisionStream({ url: "http://api.test/decisions/stream", eventSourceFactory: factory }),
    );

    expect(result.current.status).toBe("connecting");
    expect(result.current.items).toEqual([]);

    act(() => {
      source.emit(decision("a"));
      source.emit(decision("b"));
    });

    await waitFor(() =>
      expect(result.current.items.map((i) => i.decision.classificationId)).toEqual(["b", "a"]),
    );
  });

  it("flips a quarantine-pending card to its resolution in place, as one card", async () => {
    let source!: FakeEventSource;
    const factory = (url: string) => (source = new FakeEventSource(url)) as unknown as EventSource;

    const { result } = renderHook(() =>
      useDecisionStream({ url: "http://api.test/decisions/stream", eventSourceFactory: factory }),
    );

    act(() => {
      source.emit(decision("p", { emailId: "uncertain", tier: "quarantine", routeUsed: "llm" }));
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    expect(result.current.items[0].resolved).toBe(false);

    act(() => {
      source.emit(decision("r", { emailId: "uncertain", tier: "allow", routeUsed: "llm" }));
    });
    await waitFor(() => expect(result.current.items[0].decision.tier).toBe("allow"));
    expect(result.current.items).toHaveLength(1);
    expect(result.current.items[0].resolved).toBe(true);
  });

  it("closes the stream on unmount", () => {
    let source!: FakeEventSource;
    const factory = (url: string) => (source = new FakeEventSource(url)) as unknown as EventSource;
    const { unmount } = renderHook(() => useDecisionStream({ url: "http://api.test/x", eventSourceFactory: factory }));
    unmount();
    expect(source.close).toHaveBeenCalled();
  });
});
