// Live decision transport for the Spam Classifier Console (story 12.01).
//
// The console is a *thin* client: it subscribes to the Java API's Server-Sent
// Events feed and renders the decisions it receives — it never reimplements
// decision logic. The browser's own EventSource gives us reconnection for free,
// including replaying the last seen event id via the `Last-Event-ID` header, so
// a dropped connection resumes without lost-forever or duplicate cards (the
// server replays only events after that id; we also dedupe by classificationId).
//
// Backpressure is handled by `appendDecisions`: the rendered list is capped, so
// a high event rate can never grow memory without bound.

import { API_BASE_URL, type AnalyzeResponse } from "./api";

/** Path of the Java SSE endpoint (see DecisionStreamController). */
export const DECISION_STREAM_PATH = "/decisions/stream";

/** SSE event name the server tags each decision with (see DecisionStream). */
export const DECISION_EVENT = "decision";

/** Most recent decisions kept in the live stream — the backpressure bound. */
export const MAX_LIVE_DECISIONS = 100;

export type StreamStatus = "connecting" | "open" | "reconnecting" | "closed";

/**
 * One live card's state. A card is keyed by {@link AnalyzeResponse.emailId}, not by
 * classification: an uncertain email is decided twice (story 05.06) — first
 * provisionally withheld as quarantine-pending, then resolved to a final tier by the
 * async LLM — and the console shows that as a single card flipping in place, never two
 * cards and never a retraction.
 */
export interface StreamItem {
  /** The latest decision recorded for this email (a resolution supersedes the pending row). */
  decision: AnalyzeResponse;
  /** True once a follow-up decision has arrived — the quarantine-pending → resolved flip. */
  resolved: boolean;
  /**
   * Every classificationId folded into this card. Folding is idempotent against this set, so
   * a reconnect replay of an earlier (pending) row is ignored rather than retracting the
   * resolution — the SSE sequence ids guarantee a newer decision is never replayed as older.
   */
  classificationIds: string[];
}

export interface DecisionStreamHandle {
  close(): void;
}

export interface DecisionStreamOptions {
  /** Called for each decision received, newest last. */
  onDecision(decision: AnalyzeResponse): void;
  /** Called whenever the connection status changes. */
  onStatus?(status: StreamStatus): void;
  /** Override the stream URL (defaults to the configured API base). */
  url?: string;
  /** Injectable EventSource constructor (jsdom has none; tests pass a fake). */
  eventSourceFactory?: (url: string) => EventSource;
}

/**
 * Folds an incoming batch of decisions into the existing cards (newest first), keyed by
 * email. A decision for an email not yet on screen prepends a new card; a decision for an
 * email already on screen is its resolution — it updates that card <em>in place</em> (no
 * jump, never moved to the top) and marks it resolved, which is the quarantine-pending →
 * final-tier flip (story 05.06). Folding is idempotent: a classificationId already absorbed
 * by some card is skipped, so a reconnect replay never doubles a card nor retracts a
 * resolution. The result is capped at {@link MAX_LIVE_DECISIONS}, so memory stays bounded
 * under a high event rate. Pure — the same inputs always produce the same list.
 */
export function appendDecisions(
  current: StreamItem[],
  incoming: AnalyzeResponse[],
  max: number = MAX_LIVE_DECISIONS,
): StreamItem[] {
  if (incoming.length === 0) {
    return current;
  }

  let next = current;
  let changed = false;

  for (const decision of incoming) {
    if (next.some((item) => item.classificationIds.includes(decision.classificationId))) {
      continue; // Already folded — an idempotent reconnect replay, not a new beat.
    }
    if (!changed) {
      next = current.slice();
      changed = true;
    }
    const at = next.findIndex((item) => item.decision.emailId === decision.emailId);
    if (at >= 0) {
      // A newer decision for an on-screen email: the withheld card being resolved. Update in
      // place so the card flips where it sits, rather than re-ordering or stacking a duplicate.
      const prior = next[at];
      next[at] = {
        decision,
        resolved: true,
        classificationIds: [...prior.classificationIds, decision.classificationId],
      };
    } else {
      next.unshift({ decision, resolved: false, classificationIds: [decision.classificationId] });
    }
  }

  if (!changed) {
    return current;
  }
  return next.length > max ? next.slice(0, max) : next;
}

/**
 * Whether a card is still <em>quarantine-pending</em>: provisionally withheld on the LLM
 * route and not yet resolved (story 05.06). A pending card shows a "resolving" affordance and
 * is the start state of the pending → final-tier flip; once any follow-up decision arrives the
 * card is {@link StreamItem.resolved} and is no longer pending, whatever its final tier.
 */
export function isPending(item: StreamItem): boolean {
  return (
    !item.resolved && item.decision.routeUsed === "llm" && item.decision.tier === "quarantine"
  );
}

/** Parses an SSE `data` payload into a decision. Throws on malformed JSON. */
export function parseDecision(data: string): AnalyzeResponse {
  return JSON.parse(data) as AnalyzeResponse;
}

/**
 * Opens the live decision stream. Returns a handle whose `close()` tears the
 * subscription down. Reconnection is delegated to the browser's EventSource;
 * `onStatus` reflects the connection lifecycle for the UI's live indicator.
 */
export function openDecisionStream(options: DecisionStreamOptions): DecisionStreamHandle {
  const url = options.url ?? `${API_BASE_URL}${DECISION_STREAM_PATH}`;
  const factory =
    options.eventSourceFactory ?? ((streamUrl: string) => new EventSource(streamUrl));
  const status = (next: StreamStatus) => options.onStatus?.(next);

  status("connecting");
  const source = factory(url);

  source.addEventListener(DECISION_EVENT, (event) => {
    try {
      options.onDecision(parseDecision((event as MessageEvent).data));
    } catch {
      // A single malformed frame must not kill the stream; skip it.
    }
  });

  source.onopen = () => status("open");
  source.onerror = () => {
    // EventSource auto-reconnects unless it has permanently closed.
    status(source.readyState === source.CLOSED ? "closed" : "reconnecting");
  };

  return {
    close() {
      source.close();
      status("closed");
    },
  };
}
