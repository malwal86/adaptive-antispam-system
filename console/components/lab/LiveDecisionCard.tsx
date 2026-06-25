"use client";

import { motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import type { AnalyzeResponse } from "@/lib/api";
import { TIERS, reasonLabel } from "@/lib/tiers";
import { cn } from "@/lib/utils";

/**
 * A compact card for one decision in the live stream (story 12.01). It fades and
 * settles in rather than popping (animation guidelines: short, ≤300ms, ease-out),
 * collapsing to an instant appearance under reduced-motion. The richer
 * tier-flip card is story 12.03; this is the readable live row the transport
 * proves end-to-end.
 */
export function LiveDecisionCard({ decision }: { decision: AnalyzeResponse }) {
  const reduceMotion = useReducedMotion();
  const tier = TIERS[decision.tier];

  return (
    <motion.article
      initial={{ opacity: 0, y: reduceMotion ? 0 : 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reduceMotion ? 0 : 0.24, ease: [0.05, 0.7, 0.1, 1] }}
      data-testid="live-decision-card"
      data-tier={decision.tier}
      className={cn(
        "flex items-start gap-3 rounded-md border bg-surface-container/60 p-4 ring-1",
        tier.accentBorder,
        tier.ring,
      )}
    >
      <span
        className={cn(
          "mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-md bg-surface/50",
          tier.accentText,
        )}
      >
        <Icon name={tier.icon} filled className="text-[22px]" />
      </span>

      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex items-center justify-between gap-3">
          <span className={cn("text-title-sm font-medium", tier.accentText)} data-testid="tier-label">
            {tier.label}
          </span>
          <span className="flex items-center gap-3 text-label-md text-on-surface-variant">
            <span className="inline-flex items-center gap-1" data-testid="route-used">
              <Icon
                name={decision.routeUsed === "hard_rule" ? "gavel" : "memory"}
                className="text-[16px] leading-none"
              />
              {routeLabel(decision.routeUsed)}
            </span>
            <span className="inline-flex items-center gap-1">
              <Icon name="timer" className="text-[16px] leading-none" />
              {decision.latencyMs} ms
            </span>
          </span>
        </div>

        {decision.reasonCodes.length > 0 && (
          <div className={cn("flex flex-wrap gap-1.5", tier.accentText)}>
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
          </div>
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

function routeLabel(route: string): string {
  if (route === "hard_rule") return "Hard rule";
  if (route === "model") return "Model";
  if (route === "llm") return "LLM";
  return route;
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString();
}
