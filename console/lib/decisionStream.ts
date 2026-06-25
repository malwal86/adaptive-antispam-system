// Live decision transport for the Abuse Lab Console (story 12.01).
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
 * Folds an incoming batch of decisions into the existing list (newest first),
 * de-duplicating by classificationId (so a reconnect replay never doubles a card)
 * and capping the result at {@link MAX_LIVE_DECISIONS} (so memory stays bounded
 * under a high event rate). Pure — the same inputs always produce the same list.
 */
export function appendDecisions(
  current: AnalyzeResponse[],
  incoming: AnalyzeResponse[],
  max: number = MAX_LIVE_DECISIONS,
): AnalyzeResponse[] {
  if (incoming.length === 0) {
    return current;
  }
  const seen = new Set(current.map((d) => d.classificationId));
  const fresh = incoming.filter((d) => {
    if (seen.has(d.classificationId)) {
      return false;
    }
    seen.add(d.classificationId);
    return true;
  });
  if (fresh.length === 0) {
    return current;
  }
  // Newest first: most-recent of the incoming batch leads, then the prior list.
  return [...fresh.reverse(), ...current].slice(0, max);
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
