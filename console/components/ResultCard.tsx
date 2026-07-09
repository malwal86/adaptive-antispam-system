"use client";

import { motion, useReducedMotion, type Variants } from "framer-motion";
import { Card } from "@/components/ui/card";
import { Icon } from "@/components/ui/icon";
import { ReasonChip } from "@/components/ReasonChip";
import type { AnalyzeResponse } from "@/lib/api";
import { EMPHASIZED_EASE } from "@/lib/animation";
import { formatClockTime, shortId } from "@/lib/format";
import { routeLabel, TIERS } from "@/lib/tiers";
import { cn } from "@/lib/utils";

/**
 * The decision card. Each of the four tiers is visually distinct (hue + icon +
 * container), and the card fades-and-settles in rather than popping (animation
 * guidelines: short, ≤300ms, ease-out). Reason chips stagger in after the verdict.
 * Reduced-motion collapses all of this to an instant appearance.
 */
export function ResultCard({ result }: { result: AnalyzeResponse }) {
  const reduceMotion = useReducedMotion();
  const tier = TIERS[result.tier];

  // Time-based, eased entrance (not per-frame). Disabled under reduced-motion.
  const container: Variants = {
    hidden: { opacity: 0, y: reduceMotion ? 0 : 12, scale: reduceMotion ? 1 : 0.98 },
    show: {
      opacity: 1,
      y: 0,
      scale: 1,
      transition: reduceMotion
        ? { duration: 0 }
        : { duration: 0.28, ease: EMPHASIZED_EASE, when: "beforeChildren", staggerChildren: 0.05 },
    },
  };
  const item: Variants = {
    hidden: { opacity: 0, y: reduceMotion ? 0 : 6 },
    show: { opacity: 1, y: 0, transition: { duration: reduceMotion ? 0 : 0.2, ease: "easeOut" } },
  };

  return (
    <motion.div
      // Keying on classificationId re-runs the entrance each new decision.
      key={result.classificationId}
      variants={container}
      initial="hidden"
      animate="show"
      data-testid="result-card"
      data-tier={result.tier}
    >
      <Card className={cn("overflow-hidden ring-1", tier.containerBg, tier.ring)}>
        <div className="flex flex-col gap-5 p-6">
          {/* Verdict header: tier icon + label, and the route/latency telemetry. */}
          <div className="flex items-start justify-between gap-4">
            <motion.div variants={item} className={cn("flex items-center gap-3", tier.accentText)}>
              <span
                className={cn(
                  "grid h-12 w-12 place-items-center rounded-md bg-surface/40 ring-1",
                  tier.ring,
                )}
              >
                <Icon name={tier.icon} filled className="text-[28px]" />
              </span>
              <div>
                <div className="text-headline-sm font-medium" data-testid="tier-label">
                  {tier.label}
                </div>
                <div className="text-body-md text-on-surface-variant">{tier.blurb}</div>
              </div>
            </motion.div>

            <motion.div variants={item} className="flex flex-col items-end gap-1 text-right">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-outline/60 bg-surface/40 px-3 py-1 text-label-md text-on-surface-variant">
                <Icon
                  name={result.routeUsed === "hard_rule" ? "gavel" : "memory"}
                  className="text-[16px] leading-none"
                />
                <span data-testid="route-used">{routeLabel(result.routeUsed)}</span>
              </span>
              <span className="inline-flex items-center gap-1.5 text-label-md text-on-surface-variant">
                <Icon name="timer" className="text-[16px] leading-none" />
                <span data-testid="latency">{result.latencyMs} ms</span>
              </span>
            </motion.div>
          </div>

          {/* Grounded explanation. */}
          <motion.p variants={item} className="text-body-lg text-on-surface" data-testid="explanation">
            {result.explanation}
          </motion.p>

          {/* Reason chips (machine codes), if any fired. */}
          {result.reasonCodes.length > 0 && (
            <motion.div variants={item} className={cn("flex flex-wrap gap-2", tier.accentText)}>
              {result.reasonCodes.map((code) => (
                <ReasonChip key={code} code={code} />
              ))}
            </motion.div>
          )}

          {/* Provenance footer. */}
          <motion.div
            variants={item}
            className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-outline/40 pt-4 text-label-md text-on-surface-variant"
          >
            <span className="inline-flex items-center gap-1.5">
              <Icon name="fingerprint" className="text-[16px] leading-none" />
              {shortId(result.emailId)}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <Icon name="schedule" className="text-[16px] leading-none" />
              {formatClockTime(result.decidedAt)}
            </span>
            {result.duplicate && (
              <span className="inline-flex items-center gap-1.5">
                <Icon name="content_copy" className="text-[16px] leading-none" />
                re-analysed (already ingested)
              </span>
            )}
          </motion.div>
        </div>
      </Card>
    </motion.div>
  );
}
