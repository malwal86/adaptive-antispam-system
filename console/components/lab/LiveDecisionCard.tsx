"use client";

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { EMPHASIZED_EASE } from "@/lib/animation";
import { isPending, type StreamItem } from "@/lib/decisionStream";
import { shortId } from "@/lib/format";
import { TIERS, outcomeFor, plainReason } from "@/lib/tiers";
import { cn } from "@/lib/utils";

/**
 * One decision in the live stream, rendered as the email it is (story 12.03): who it's from, its
 * subject, a one-line preview, and — in plain language — where it landed and why. The point is that
 * anyone, technical or not, can read a card at a glance: a green "Delivered to inbox" or a red "Moved
 * to spam", not a hash, a latency, and a reason code.
 *
 * <p>The card is keyed by email upstream, so an uncertain message that is first withheld
 * (quarantine-pending) and then resolved by the LLM (story 05.06) is this <em>same</em> card flipping
 * in place: the outcome badge rotates from "Checking…" to the final inbox/spam verdict, the accent
 * colour eases across, and the reason updates — never a second card and never a retraction. While
 * pending it shows a slow-pulsing "checking" affordance. Reduced-motion collapses every motion to an
 * instant, legible state.
 */
export function LiveDecisionCard({ item }: { item: StreamItem }) {
  const reduceMotion = useReducedMotion();
  const { decision, resolved } = item;
  const tier = TIERS[decision.tier];
  const pending = isPending(item);
  const outcome = outcomeFor(decision.tier, decision.delivered);
  const reason = plainReason(decision.reasonCodes, outcome.folder, decision.explanation);

  // The envelope, when the feed enriched it (scenario mail); otherwise fall back to a short id so the
  // card is never blank for ordinary traffic.
  const sender = decision.sender ?? shortId(decision.emailId);
  const subject = decision.subject;

  return (
    <motion.article
      initial={{ opacity: 0, y: reduceMotion ? 0 : 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reduceMotion ? 0 : 0.24, ease: EMPHASIZED_EASE }}
      data-testid="live-decision-card"
      data-tier={decision.tier}
      data-folder={outcome.folder}
      data-pending={pending ? "true" : undefined}
      data-resolved={resolved ? "true" : undefined}
      className={cn(
        "flex items-start gap-3 rounded-md border bg-surface-container/60 p-4 ring-1 transition-colors duration-300",
        pending ? "border-tier-quarantine/50 ring-tier-quarantine/40" : cn(tier.accentBorder, tier.ring),
      )}
    >
      {/* Outcome badge: rotates from "checking" to the final inbox/spam icon when resolved. */}
      <AnimatePresence mode="wait" initial={false}>
        <motion.span
          key={pending ? "checking" : decision.tier}
          initial={{ opacity: 0, rotateX: reduceMotion ? 0 : 90 }}
          animate={{ opacity: 1, rotateX: 0 }}
          exit={{ opacity: 0, rotateX: reduceMotion ? 0 : -90 }}
          transition={{ duration: reduceMotion ? 0 : 0.22, ease: "easeOut" }}
          style={{ transformPerspective: 420 }}
          className={cn(
            "mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-md bg-surface/50",
            pending ? "text-tier-quarantine" : tier.accentText,
          )}
        >
          <Icon name={pending ? "hourglass_top" : outcome.icon} filled className="text-[22px]" />
        </motion.span>
      </AnimatePresence>

      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        {/* From · Subject — the card reads like a line in an inbox. */}
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="truncate text-title-sm font-medium text-on-surface" data-testid="card-sender">
            {sender}
          </span>
          {subject && (
            <span className="truncate text-body-md text-on-surface-variant" data-testid="card-subject">
              {subject}
            </span>
          )}
        </div>

        {decision.preview && (
          <p className="line-clamp-2 text-label-md text-on-surface-variant/80" data-testid="card-preview">
            {decision.preview}
          </p>
        )}

        {/* Outcome + plain reason. While pending, the outcome reads "Checking…". */}
        <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1">
          {pending ? (
            <CheckingChip reduceMotion={reduceMotion} />
          ) : (
            <span
              data-testid="card-outcome"
              className={cn(
                "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-label-md font-medium",
                tier.accentText,
                tier.accentBorder,
                tier.containerBg,
              )}
            >
              <Icon name={outcome.folder === "inbox" ? "inbox" : "report"} className="text-[14px] leading-none" />
              {outcome.verb}
            </span>
          )}
          <span className="text-label-md text-on-surface-variant" data-testid="card-reason">
            {pending ? "Taking a closer look at this one…" : reason}
          </span>
        </div>
      </div>
    </motion.article>
  );
}

/** The quarantine-pending affordance: a slow pulse says "held, checking" without a spinner. */
function CheckingChip({ reduceMotion }: { reduceMotion: boolean | null }) {
  return (
    <motion.span
      data-testid="pending-indicator"
      animate={reduceMotion ? undefined : { opacity: [0.6, 1, 0.6] }}
      transition={reduceMotion ? undefined : { duration: 1.2, repeat: Infinity, ease: "easeInOut" }}
      className="inline-flex shrink-0 items-center gap-1 rounded-full border border-tier-quarantine/40 bg-tier-quarantine-container px-2 py-0.5 text-label-md font-medium text-tier-quarantine"
    >
      <Icon name="hourglass_top" className="text-[14px] leading-none" />
      Checking…
    </motion.span>
  );
}
