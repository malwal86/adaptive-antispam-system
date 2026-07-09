"use client";

import { motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import type { TrustPoint } from "@/lib/streamStats";
import { cn } from "@/lib/utils";

const VIEW_W = 100;
const VIEW_H = 40;

/** A collapse from the running peak this large reads as "reputation gave way under attack". */
const COLLAPSE_DROP = 0.25;

/**
 * The live reputation curve (story 12.04). Each fused decision contributes a trust sample
 * (1 − reputation-fused posterior), so the curve <em>rises</em> as good mail earns trust during a
 * warm-up and <em>collapses</em> as an attack drives the posterior up — bound to the real
 * {@code posterior} on the feed, never a scripted animation. The newest point pulses; when trust has
 * fallen sharply off its peak the panel flags the collapse, the headline beat of the thunderclap
 * (story 12.05).
 */
export function ReputationChart({ series }: { series: TrustPoint[] }) {
  const reduceMotion = useReducedMotion();

  if (series.length < 2) {
    return (
      <div
        data-testid="reputation-empty"
        className="flex h-28 flex-col items-center justify-center gap-1 rounded-md border border-dashed border-outline/50 text-center text-on-surface-variant"
      >
        <Icon name="trending_up" className="text-[28px] opacity-70" />
        <p className="text-label-md">Trust curve builds as fused decisions arrive.</p>
      </div>
    );
  }

  const points = series.map((point, index) => ({
    x: (index / (series.length - 1)) * VIEW_W,
    y: (1 - point.trust) * VIEW_H,
  }));
  const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${round(p.x)},${round(p.y)}`).join(" ");
  const area = `${line} L${round(points[points.length - 1].x)},${VIEW_H} L${round(points[0].x)},${VIEW_H} Z`;

  const latest = series[series.length - 1].trust;
  const peak = series.reduce((max, p) => Math.max(max, p.trust), 0);
  const collapsing = peak - latest >= COLLAPSE_DROP;
  const last = points[points.length - 1];

  return (
    <div data-testid="reputation-chart" data-collapsing={collapsing ? "true" : undefined}>
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-label-md text-on-surface-variant">How much we trust the sender</span>
        <span
          className={cn(
            "tabular-nums text-label-md font-medium",
            collapsing ? "text-tier-block" : "text-on-surface",
          )}
          data-testid="reputation-latest"
        >
          {Math.round(latest * 100)}%
        </span>
      </div>

      <svg
        viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
        preserveAspectRatio="none"
        className="h-28 w-full"
        role="img"
        aria-label="Live sender trust curve"
      >
        <defs>
          <linearGradient id="trust-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgb(var(--m3-primary))" stopOpacity="0.28" />
            <stop offset="100%" stopColor="rgb(var(--m3-primary))" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={area} fill="url(#trust-fill)" />
        <path
          d={line}
          fill="none"
          stroke={collapsing ? "rgb(var(--tier-block))" : "rgb(var(--m3-primary))"}
          strokeWidth={1.5}
          strokeLinejoin="round"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
        <motion.circle
          r={2}
          fill={collapsing ? "rgb(var(--tier-block))" : "rgb(var(--m3-primary))"}
          animate={reduceMotion ? { cx: last.x, cy: last.y } : { cx: last.x, cy: last.y, opacity: [1, 0.4, 1] }}
          transition={
            reduceMotion
              ? { duration: 0 }
              : { cx: { duration: 0.3 }, cy: { duration: 0.3 }, opacity: { duration: 1.4, repeat: Infinity } }
          }
        />
      </svg>

      {collapsing && (
        <motion.p
          data-testid="reputation-collapse"
          initial={{ opacity: 0, y: reduceMotion ? 0 : 4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: reduceMotion ? 0 : 0.24 }}
          className="mt-1.5 inline-flex items-center gap-1 text-label-md text-tier-block"
        >
          <Icon name="trending_down" className="text-[16px] leading-none" />
          Reputation collapsing under attack
        </motion.p>
      )}
    </div>
  );
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
