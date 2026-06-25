import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useDecisionStream } from "@/lib/useDecisionStream";
import type { AnalyzeResponse } from "@/lib/api";

function decision(id: string): AnalyzeResponse {
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
    expect(result.current.decisions).toEqual([]);

    act(() => {
      source.emit(decision("a"));
      source.emit(decision("b"));
    });

    await waitFor(() =>
      expect(result.current.decisions.map((d) => d.classificationId)).toEqual(["b", "a"]),
    );
  });

  it("closes the stream on unmount", () => {
    let source!: FakeEventSource;
    const factory = (url: string) => (source = new FakeEventSource(url)) as unknown as EventSource;
    const { unmount } = renderHook(() => useDecisionStream({ url: "http://api.test/x", eventSourceFactory: factory }));
    unmount();
    expect(source.close).toHaveBeenCalled();
  });
});
