"use client";

import { motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { EMPHASIZED_EASE } from "@/lib/animation";
import {
  ROUTE_BUCKETS,
  routeFraction,
  type RouteBucket,
  type StreamStats,
} from "@/lib/streamStats";
import { cn } from "@/lib/utils";

interface RouteMeta {
  label: string;
  icon: string;
  /** Fill for the bar segment. */
  fill: string;
  /** Text/dot accent in the legend. */
  accent: string;
}

// The three routes the pipeline can take, in escalation order: deterministic rules → the
// reputation-fused content model → the expensive LLM. Each maps to a real `routeUsed` token.
const ROUTE_META: Record<RouteBucket, RouteMeta> = {
  rules: { label: "Rules", icon: "gavel", fill: "bg-primary", accent: "text-primary" },
  reputation: { label: "Reputation", icon: "memory", fill: "bg-tier-allow", accent: "text-tier-allow" },
  llm: { label: "LLM", icon: "smart_toy", fill: "bg-tier-warn", accent: "text-tier-warn" },
};

/**
 * The route_used stacked bar (story 12.04): the live rules/reputation/LLM split of how decisions
 * were reached, bound to the real {@code routeUsed} on every streamed decision. As an attack drives
 * uncertain mail to the LLM, the LLM segment visibly grows — the routing shift the thunderclap shows
 * (story 12.05). Segment widths ease to their new share rather than jumping.
 */
export function RouteMixBar({ stats }: { stats: StreamStats }) {
  const reduceMotion = useReducedMotion();

  return (
    <div data-testid="route-mix">
      <div className="mb-1.5 flex items-center justify-between text-label-md text-on-surface-variant">
        <span>How it decided</span>
        <span className="tabular-nums" data-testid="route-total">
          {stats.total} decided
        </span>
      </div>

      <div className="flex h-3 w-full overflow-hidden rounded-full bg-surface-variant/60">
        {stats.total === 0 ? null : (
          ROUTE_BUCKETS.map((bucket) => {
            const fraction = routeFraction(stats, bucket);
            return (
              <motion.div
                key={bucket}
                data-testid="route-segment"
                data-bucket={bucket}
                className={cn("h-full", ROUTE_META[bucket].fill)}
                initial={false}
                animate={{ width: `${fraction * 100}%` }}
                transition={{ duration: reduceMotion ? 0 : 0.4, ease: EMPHASIZED_EASE }}
              />
            );
          })
        )}
      </div>

      <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {ROUTE_BUCKETS.map((bucket) => (
          <li key={bucket} className="inline-flex items-center gap-1.5 text-label-md">
            <Icon name={ROUTE_META[bucket].icon} className={cn("text-[16px] leading-none", ROUTE_META[bucket].accent)} />
            <span className="text-on-surface-variant">{ROUTE_META[bucket].label}</span>
            <span className="tabular-nums font-medium text-on-surface" data-testid={`route-count-${bucket}`}>
              {stats.routeCounts[bucket]}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
