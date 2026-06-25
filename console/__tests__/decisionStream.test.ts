import { describe, expect, it, vi } from "vitest";
import {
  appendDecisions,
  openDecisionStream,
  parseDecision,
  MAX_LIVE_DECISIONS,
  type StreamStatus,
} from "@/lib/decisionStream";
import type { AnalyzeResponse } from "@/lib/api";

function decision(id: string, overrides: Partial<AnalyzeResponse> = {}): AnalyzeResponse {
  return {
    emailId: `email-${id}`,
    classificationId: id,
    tier: "block",
    reasonCodes: ["KNOWN_BAD_URL"],
    routeUsed: "hard_rule",
    latencyMs: 2,
    explanation: "Blocked.",
    decidedAt: "2026-06-05T12:00:00Z",
    duplicate: false,
    ...overrides,
  };
}

describe("appendDecisions", () => {
  it("prepends newest-first", () => {
    const list = appendDecisions([decision("a")], [decision("b")]);
    expect(list.map((d) => d.classificationId)).toEqual(["b", "a"]);
  });

  it("folds a batch newest-first (last of the batch leads)", () => {
    const list = appendDecisions([], [decision("a"), decision("b"), decision("c")]);
    expect(list.map((d) => d.classificationId)).toEqual(["c", "b", "a"]);
  });

  it("de-duplicates by classificationId so a reconnect replay never doubles a card", () => {
    const list = appendDecisions([decision("a"), decision("b")], [decision("b"), decision("c")]);
    expect(list.map((d) => d.classificationId)).toEqual(["c", "a", "b"]);
  });

  it("caps the list to bound memory under a high event rate", () => {
    const incoming = Array.from({ length: MAX_LIVE_DECISIONS + 25 }, (_, i) => decision(`d${i}`));
    const list = appendDecisions([], incoming);
    expect(list).toHaveLength(MAX_LIVE_DECISIONS);
  });

  it("returns the same reference when nothing fresh arrives", () => {
    const current = [decision("a")];
    expect(appendDecisions(current, [])).toBe(current);
    expect(appendDecisions(current, [decision("a")])).toBe(current);
  });
});

describe("parseDecision", () => {
  it("parses a JSON SSE data frame", () => {
    expect(parseDecision(JSON.stringify(decision("x"))).classificationId).toBe("x");
  });
});

/** Minimal in-memory EventSource stand-in (jsdom has none). */
class FakeEventSource {
  static CONNECTING = 0 as const;
  static OPEN = 1 as const;
  static CLOSED = 2 as const;
  readonly CLOSED = FakeEventSource.CLOSED;
  readyState = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, (e: MessageEvent) => void>();

  constructor(public url: string) {}

  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    this.listeners.set(type, cb);
  }
  close = vi.fn(() => {
    this.readyState = FakeEventSource.CLOSED;
  });

  emit(data: unknown) {
    this.listeners.get("decision")?.({ data: JSON.stringify(data) } as MessageEvent);
  }
  open() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.();
  }
  drop(closed: boolean) {
    this.readyState = closed ? FakeEventSource.CLOSED : FakeEventSource.CONNECTING;
    this.onerror?.();
  }
}

describe("openDecisionStream", () => {
  it("delivers decisions and tracks connection status", () => {
    const received: AnalyzeResponse[] = [];
    const statuses: StreamStatus[] = [];
    let source!: FakeEventSource;

    const handle = openDecisionStream({
      url: "http://api.test/decisions/stream",
      onDecision: (d) => received.push(d),
      onStatus: (s) => statuses.push(s),
      eventSourceFactory: (url) => (source = new FakeEventSource(url)) as unknown as EventSource,
    });

    expect(source.url).toBe("http://api.test/decisions/stream");
    expect(statuses).toEqual(["connecting"]);

    source.open();
    source.emit(decision("a"));
    expect(received.map((d) => d.classificationId)).toEqual(["a"]);
    expect(statuses).toEqual(["connecting", "open"]);

    source.drop(false);
    expect(statuses.at(-1)).toBe("reconnecting");

    handle.close();
    expect(source.close).toHaveBeenCalledOnce();
    expect(statuses.at(-1)).toBe("closed");
  });

  it("survives a malformed frame without throwing", () => {
    let source!: FakeEventSource;
    const received: AnalyzeResponse[] = [];
    openDecisionStream({
      url: "http://api.test/x",
      onDecision: (d) => received.push(d),
      eventSourceFactory: (url) => (source = new FakeEventSource(url)) as unknown as EventSource,
    });
    // Directly invoke the decision listener with bad data.
    expect(() =>
      (source as unknown as { listeners: Map<string, (e: MessageEvent) => void> }).listeners
        .get("decision")?.({ data: "not json" } as MessageEvent),
    ).not.toThrow();
    expect(received).toHaveLength(0);
  });
});
