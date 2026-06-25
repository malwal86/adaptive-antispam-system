import { describe, expect, it, vi } from "vitest";
import {
  appendDecisions,
  isPending,
  openDecisionStream,
  parseDecision,
  MAX_LIVE_DECISIONS,
  type StreamItem,
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

/** A settled (not-yet-resolved) card around a single decision, for seeding `current`. */
function card(id: string, overrides: Partial<AnalyzeResponse> = {}): StreamItem {
  const d = decision(id, overrides);
  return { decision: d, resolved: false, classificationIds: [d.classificationId] };
}

const ids = (list: StreamItem[]) => list.map((item) => item.decision.classificationId);
const tiers = (list: StreamItem[]) => list.map((item) => item.decision.tier);

describe("appendDecisions", () => {
  it("prepends newest-first", () => {
    const list = appendDecisions([card("a")], [decision("b")]);
    expect(ids(list)).toEqual(["b", "a"]);
  });

  it("folds a batch newest-first (last of the batch leads)", () => {
    const list = appendDecisions([], [decision("a"), decision("b"), decision("c")]);
    expect(ids(list)).toEqual(["c", "b", "a"]);
  });

  it("de-duplicates by classificationId so a reconnect replay never doubles a card", () => {
    const list = appendDecisions([card("a"), card("b")], [decision("b"), decision("c")]);
    expect(ids(list)).toEqual(["c", "a", "b"]);
  });

  it("marks a fresh single decision as unresolved", () => {
    const list = appendDecisions([], [decision("a")]);
    expect(list[0].resolved).toBe(false);
  });

  it("collapses a quarantine-pending email and its resolution into one card, flipped in place", () => {
    const pending = decision("p", {
      emailId: "e1",
      tier: "quarantine",
      routeUsed: "llm",
      reasonCodes: [],
    });
    const resolvedTo = decision("r", {
      emailId: "e1",
      tier: "allow",
      routeUsed: "llm",
      reasonCodes: [],
    });

    // The pending card arrives first, then a later (unrelated) card lands on top of it;
    // the resolution must update the pending card where it sits, not jump it back to the top.
    const list = appendDecisions([], [pending, decision("n", { emailId: "e0" }), resolvedTo]);

    expect(list).toHaveLength(2);
    const resolved = list.find((item) => item.decision.emailId === "e1")!;
    expect(resolved.decision.classificationId).toBe("r");
    expect(resolved.decision.tier).toBe("allow");
    expect(resolved.resolved).toBe(true);
    expect(resolved.classificationIds).toEqual(["p", "r"]);
    // In place: "n" still leads, the resolved card stays behind it.
    expect(ids(list)).toEqual(["n", "r"]);
  });

  it("never retracts a resolution when an earlier pending row is replayed", () => {
    const pending = decision("p", { emailId: "e1", tier: "quarantine", routeUsed: "llm" });
    const resolvedTo = decision("r", { emailId: "e1", tier: "allow", routeUsed: "llm" });
    const settled = appendDecisions([], [pending, resolvedTo]);

    // A reconnect replays both rows; the fold is idempotent, so nothing changes.
    const replayed = appendDecisions(settled, [pending, resolvedTo]);
    expect(replayed).toBe(settled);
    expect(tiers(replayed)).toEqual(["allow"]);
  });

  it("keeps distinct emails as separate cards", () => {
    const list = appendDecisions([], [decision("a", { emailId: "e1" }), decision("b", { emailId: "e2" })]);
    expect(list).toHaveLength(2);
  });

  it("caps the list to bound memory under a high event rate", () => {
    const incoming = Array.from({ length: MAX_LIVE_DECISIONS + 25 }, (_, i) => decision(`d${i}`));
    const list = appendDecisions([], incoming);
    expect(list).toHaveLength(MAX_LIVE_DECISIONS);
  });

  it("returns the same reference when nothing fresh arrives", () => {
    const current = [card("a")];
    expect(appendDecisions(current, [])).toBe(current);
    expect(appendDecisions(current, [decision("a")])).toBe(current);
  });
});

describe("isPending", () => {
  it("is pending while an LLM-route quarantine is unresolved", () => {
    expect(isPending(card("p", { tier: "quarantine", routeUsed: "llm" }))).toBe(true);
  });

  it("is not pending once resolved, whatever the final tier", () => {
    const confirmed: StreamItem = {
      ...card("r", { tier: "quarantine", routeUsed: "llm" }),
      resolved: true,
    };
    expect(isPending(confirmed)).toBe(false);
  });

  it("is not pending for a final model-route quarantine or any other tier", () => {
    expect(isPending(card("m", { tier: "quarantine", routeUsed: "model" }))).toBe(false);
    expect(isPending(card("b", { tier: "block", routeUsed: "hard_rule" }))).toBe(false);
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
