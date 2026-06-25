"use client";

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { isPending, type StreamItem } from "@/lib/decisionStream";
import { TIERS, reasonLabel } from "@/lib/tiers";
import { cn } from "@/lib/utils";

// M3 emphasized-decelerate — settle at rest. Both short (≤300ms) per the guidelines.
const EMPHASIZED = [0.05, 0.7, 0.1, 1] as const;

/**
 * One decision in the live stream, as a card that flips to its tier (story 12.03).
 *
 * <p>The card is keyed by email upstream, so an uncertain message that is first withheld
 * (quarantine-pending) and then resolved by the LLM (story 05.06) is this <em>same</em> card
 * flipping in place: the tier badge rotates to the new tier, the accent colour eases across,
 * and the reason chips fade in — never a second card and never a retraction. While pending it
 * shows a slow-pulsing "resolving" affordance (indeterminate progress, per the guidelines).
 * Reduced-motion collapses every motion to an instant, legible state.
 */
export function LiveDecisionCard({ item }: { item: StreamItem }) {
  const reduceMotion = useReducedMotion();
  const { decision, resolved } = item;
  const tier = TIERS[decision.tier];
  const pending = isPending(item);

  return (
    <motion.article
      initial={{ opacity: 0, y: reduceMotion ? 0 : 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reduceMotion ? 0 : 0.24, ease: EMPHASIZED }}
      data-testid="live-decision-card"
      data-tier={decision.tier}
      data-pending={pending ? "true" : undefined}
      data-resolved={resolved ? "true" : undefined}
      className={cn(
        "flex items-start gap-3 rounded-md border bg-surface-container/60 p-4 ring-1 transition-colors duration-300",
        tier.accentBorder,
        tier.ring,
      )}
    >
      {/* Tier badge: rotates to the new tier on a resolution (cross-fade + flip, ≤300ms). */}
      <AnimatePresence mode="wait" initial={false}>
        <motion.span
          key={decision.tier}
          initial={{ opacity: 0, rotateX: reduceMotion ? 0 : 90 }}
          animate={{ opacity: 1, rotateX: 0 }}
          exit={{ opacity: 0, rotateX: reduceMotion ? 0 : -90 }}
          transition={{ duration: reduceMotion ? 0 : 0.22, ease: "easeOut" }}
          style={{ transformPerspective: 420 }}
          className={cn(
            "mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-md bg-surface/50",
            tier.accentText,
          )}
        >
          <Icon name={tier.icon} filled className="text-[22px]" />
        </motion.span>
      </AnimatePresence>

      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex items-center justify-between gap-3">
          <span className="flex min-w-0 items-center gap-2">
            <span
              className={cn("text-title-sm font-medium transition-colors duration-300", tier.accentText)}
              data-testid="tier-label"
            >
              {tier.label}
            </span>
            {pending && <PendingChip reduceMotion={reduceMotion} />}
          </span>
          <span className="flex shrink-0 items-center gap-3 text-label-md text-on-surface-variant">
            <span className="inline-flex items-center gap-1" data-testid="route-used">
              <Icon name={routeIcon(decision.routeUsed)} className="text-[16px] leading-none" />
              {routeLabel(decision.routeUsed)}
            </span>
            <span className="inline-flex items-center gap-1">
              <Icon name="timer" className="text-[16px] leading-none" />
              {decision.latencyMs} ms
            </span>
          </span>
        </div>

        {decision.reasonCodes.length > 0 && (
          <motion.div
            initial={{ opacity: 0, y: reduceMotion ? 0 : 4 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: reduceMotion ? 0 : 0.2, ease: "easeOut" }}
            className={cn("flex flex-wrap gap-1.5", tier.accentText)}
          >
            {decision.reasonCodes.map((code) => (
              <span
                key={code}
                data-testid="reason-chip"
                className={cn(
                  "rounded-full border px-2 py-0.5 text-label-md",
                  tier.accentBorder,
                  tier.containerBg,
                )}
              >
                {reasonLabel(code)}
              </span>
            ))}
          </motion.div>
        )}

        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-label-md text-on-surface-variant">
          <span className="inline-flex items-center gap-1">
            <Icon name="fingerprint" className="text-[16px] leading-none" />
            {decision.emailId.slice(0, 8)}
          </span>
          <span className="inline-flex items-center gap-1">
            <Icon name="schedule" className="text-[16px] leading-none" />
            {formatTime(decision.decidedAt)}
          </span>
        </div>
      </div>
    </motion.article>
  );
}

/** The quarantine-pending affordance: a slow pulse says "withheld, resolving" without a spinner. */
function PendingChip({ reduceMotion }: { reduceMotion: boolean | null }) {
  return (
    <motion.span
      data-testid="pending-indicator"
      animate={reduceMotion ? undefined : { opacity: [0.6, 1, 0.6] }}
      transition={reduceMotion ? undefined : { duration: 1.2, repeat: Infinity, ease: "easeInOut" }}
      className="inline-flex shrink-0 items-center gap-1 rounded-full border border-tier-quarantine/40 bg-tier-quarantine-container px-2 py-0.5 text-label-md text-tier-quarantine"
    >
      <Icon name="hourglass_top" className="text-[14px] leading-none" />
      Resolving…
    </motion.span>
  );
}

function routeLabel(route: string): string {
  if (route === "hard_rule") return "Hard rule";
  if (route === "model") return "Model";
  if (route === "llm") return "LLM";
  return route;
}

function routeIcon(route: string): string {
  if (route === "hard_rule") return "gavel";
  if (route === "llm") return "smart_toy";
  return "memory";
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString();
}
