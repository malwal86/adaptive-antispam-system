"use client";

import { useState } from "react";
import { AnimatePresence } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { EmailDetail } from "@/components/lab/EmailDetail";
import { LiveDecisionCard } from "@/components/lab/LiveDecisionCard";
import { StreamStatusPill } from "@/components/lab/StreamStatusPill";
import type { StreamItem, StreamStatus } from "@/lib/decisionStream";

interface LiveStreamProps {
  items: StreamItem[];
  status: StreamStatus;
}

/**
 * The centre pane: a live, newest-first stream of decision cards (stories 12.01, 12.03).
 * Presentational — it renders whatever the transport hands it, so it is trivially
 * testable and never reimplements decision logic. Cards are keyed by email, so an
 * uncertain message that is withheld then resolved (story 05.06) is one card flipping in
 * place rather than two. Clicking a card opens its detail view ({@link EmailDetail}).
 */
export function LiveStream({ items, status }: LiveStreamProps) {
  // The open detail view is tracked by email id, not by a captured snapshot, so a card that resolves
  // (quarantine-pending → final tier) while its detail is open updates in place — the same flip the
  // stream shows. When the selected email scrolls out of the capped window, the derived item is gone
  // and the dialog closes on its own.
  const [selectedEmailId, setSelectedEmailId] = useState<string | null>(null);
  const selected = selectedEmailId
    ? (items.find((item) => item.decision.emailId === selectedEmailId) ?? null)
    : null;

  return (
    <section
      aria-label="Live decision stream"
      data-testid="center-stream"
      className="flex min-h-0 flex-col gap-4"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-title-md font-medium text-on-surface">Live decisions</h2>
        <StreamStatusPill status={status} />
      </div>

      {items.length === 0 ? (
        <div
          data-testid="stream-empty"
          className="flex flex-1 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-outline/50 p-10 text-center text-on-surface-variant"
        >
          <Icon name="inbox" className="text-[40px] opacity-70" />
          <p className="text-body-md">
            Waiting for decisions. They appear here the moment the pipeline makes them.
          </p>
        </div>
      ) : (
        <ol className="flex flex-1 flex-col gap-3 overflow-y-auto pr-1">
          <AnimatePresence initial={false}>
            {items.map((item) => (
              <li key={item.decision.emailId}>
                <LiveDecisionCard
                  item={item}
                  onSelect={() => setSelectedEmailId(item.decision.emailId)}
                />
              </li>
            ))}
          </AnimatePresence>
        </ol>
      )}

      {selected && <EmailDetail item={selected} onClose={() => setSelectedEmailId(null)} />}
    </section>
  );
}
