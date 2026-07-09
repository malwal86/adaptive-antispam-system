"use client";

import { useState } from "react";
import { AnimatePresence } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { EmailDetail } from "@/components/lab/EmailDetail";
import { LiveDecisionCard } from "@/components/lab/LiveDecisionCard";
import { StreamStatusPill } from "@/components/lab/StreamStatusPill";
import { isPending, type StreamItem, type StreamStatus } from "@/lib/decisionStream";
import { outcomeFor } from "@/lib/tiers";

interface LiveStreamProps {
  items: StreamItem[];
  status: StreamStatus;
}

/** Counts of where mail landed, for the at-a-glance tally above the stream. */
function tally(items: StreamItem[]): { inbox: number; spam: number; checking: number } {
  let inbox = 0;
  let spam = 0;
  let checking = 0;
  for (const item of items) {
    if (isPending(item)) {
      checking += 1;
    } else if (outcomeFor(item.decision.tier, item.decision.delivered).folder === "inbox") {
      inbox += 1;
    } else {
      spam += 1;
    }
  }
  return { inbox, spam, checking };
}

/**
 * The centre pane: a live inbox where each email arrives as a card that lands in the inbox or gets
 * moved to spam (stories 12.01, 12.03). Presentational — it renders whatever the transport hands it,
 * so it is trivially testable and never reimplements decision logic. Cards are keyed by email, so an
 * uncertain message that is withheld then resolved (story 05.06) is one card flipping in place rather
 * than two. A running tally makes the everyday split ("delivered vs blocked") legible at a glance.
 */
export function LiveStream({ items, status }: LiveStreamProps) {
  const counts = tally(items);

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
        <div className="flex flex-col gap-0.5">
          <h2 className="text-title-md font-medium text-on-surface">Your inbox</h2>
          {items.length > 0 && (
            <p data-testid="stream-tally" className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-label-md">
              <span className="inline-flex items-center gap-1 text-tier-allow">
                <Icon name="inbox" className="text-[15px] leading-none" />
                {counts.inbox} delivered
              </span>
              <span className="inline-flex items-center gap-1 text-tier-block">
                <Icon name="report" className="text-[15px] leading-none" />
                {counts.spam} blocked
              </span>
              {counts.checking > 0 && (
                <span className="inline-flex items-center gap-1 text-tier-quarantine">
                  <Icon name="hourglass_top" className="text-[15px] leading-none" />
                  {counts.checking} checking
                </span>
              )}
            </p>
          )}
        </div>
        <StreamStatusPill status={status} />
      </div>

      {items.length === 0 ? (
        <div
          data-testid="stream-empty"
          className="flex flex-1 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-outline/50 p-10 text-center text-on-surface-variant"
        >
          <Icon name="inbox" className="text-[40px] opacity-70" />
          <p className="text-body-md">
            Your inbox is quiet. Run a scenario from the left to watch mail arrive: good mail lands
            here, scams get moved to spam.
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
