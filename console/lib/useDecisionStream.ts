"use client";

import { useEffect, useRef, useState } from "react";
import type { AnalyzeResponse } from "./api";
import {
  appendDecisions,
  openDecisionStream,
  type DecisionStreamOptions,
  type StreamItem,
  type StreamStatus,
} from "./decisionStream";
import { accumulateStats, EMPTY_STATS, type StreamStats } from "./streamStats";

export interface LiveDecisions {
  items: StreamItem[];
  status: StreamStatus;
  /** Monotonic session aggregates for the story panel (route mix, cost, trust curve) — story 12.04. */
  stats: StreamStats;
}

type HookOptions = Pick<DecisionStreamOptions, "url" | "eventSourceFactory">;

/**
 * Subscribes to the live decision stream and exposes a bounded, newest-first list
 * plus the connection status. Bursts are coalesced: decisions arriving in the
 * same frame are flushed to React state once (one render), keeping the UI
 * responsive under a high event rate. The list is capped by `appendDecisions`,
 * so memory never grows without bound. Reconnection (and gap-free resume) is
 * handled by the underlying EventSource.
 */
export function useDecisionStream(options: HookOptions = {}): LiveDecisions {
  const [items, setItems] = useState<StreamItem[]>([]);
  const [status, setStatus] = useState<StreamStatus>("connecting");
  const [stats, setStats] = useState<StreamStats>(EMPTY_STATS);

  const pending = useRef<AnalyzeResponse[]>([]);
  const frame = useRef<number | null>(null);
  // The bounded card list forgets old classificationIds, but the session aggregates must not
  // double-count a reconnect replay — so dedup the stats against a persistent seen-set of ids.
  const seen = useRef<Set<string>>(new Set());

  // The stream factory is identified by URL only; recreating it per render would
  // tear down and re-open the connection on every state change.
  const url = options.url;
  const factory = options.eventSourceFactory;

  useEffect(() => {
    const schedule =
      typeof requestAnimationFrame === "function"
        ? requestAnimationFrame
        : (cb: FrameRequestCallback) => setTimeout(() => cb(0), 16) as unknown as number;
    const cancel =
      typeof cancelAnimationFrame === "function"
        ? cancelAnimationFrame
        : (handle: number) => clearTimeout(handle);

    const flush = () => {
      frame.current = null;
      const batch = pending.current;
      pending.current = [];
      setItems((current) => appendDecisions(current, batch));
      // Fold only the decisions not seen before into the monotonic aggregates, so a replayed
      // reconnect (which the card list dedups too) never inflates the route mix or cost meter.
      const novel = batch.filter((decision) => !seen.current.has(decision.classificationId));
      if (novel.length > 0) {
        novel.forEach((decision) => seen.current.add(decision.classificationId));
        setStats((current) => accumulateStats(current, novel));
      }
    };

    const handle = openDecisionStream({
      url,
      eventSourceFactory: factory,
      onStatus: setStatus,
      onDecision: (decision) => {
        pending.current.push(decision);
        if (frame.current === null) {
          frame.current = schedule(flush);
        }
      },
    });

    return () => {
      if (frame.current !== null) {
        cancel(frame.current);
        frame.current = null;
      }
      pending.current = [];
      handle.close();
    };
  }, [url, factory]);

  return { items, status, stats };
}
