"use client";

import { motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { atCap, costRatio, formatUsd } from "@/lib/streamStats";
import { cn } from "@/lib/utils";

const EASE = [0.05, 0.7, 0.1, 1] as const;

interface CostMeterProps {
  /** Cumulative real LLM spend over the session (summed from the live feed). */
  costUsd: number;
  /** The daily cap the spend is drawn against (story 05.04 / 12.02). */
  capUsd: number;
  /** Whether the cap is actually enforced server-side (off in local/dev). */
  enforced: boolean;
}

/**
 * The cost meter (story 12.04): cumulative real LLM spend ticking up against the configured daily
 * cap. The fill is bound to summed {@code llmCostUsd} from the live feed — not a fake animation —
 * and because real spend can never exceed the server-enforced cap (story 05.04), the meter
 * <em>visibly stops</em> when it reaches it, switching to an "at cap" state. With the cap unenforced
 * (local/dev) the meter still tracks real spend against the nominal cap for the demo.
 */
export function CostMeter({ costUsd, capUsd, enforced }: CostMeterProps) {
  const reduceMotion = useReducedMotion();
  const ratio = costRatio(costUsd, capUsd);
  const capped = atCap(costUsd, capUsd);

  return (
    <div data-testid="cost-meter" data-cost-at-cap={capped ? "true" : undefined}>
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-label-md text-on-surface-variant">LLM cost</span>
        <span className="tabular-nums text-label-md text-on-surface-variant">
          <span className="font-medium text-on-surface" data-testid="cost-spent">
            {formatUsd(costUsd)}
          </span>{" "}
          / {formatUsd(capUsd)}
        </span>
      </div>

      <div className="h-3 w-full overflow-hidden rounded-full bg-surface-variant/60">
        <motion.div
          className={cn("h-full rounded-full", capped ? "bg-tier-block" : "bg-primary")}
          initial={false}
          animate={{ width: `${ratio * 100}%` }}
          transition={{ duration: reduceMotion ? 0 : 0.4, ease: EASE }}
        />
      </div>

      <div className="mt-1.5 flex items-center justify-between text-label-md">
        {capped ? (
          <span className="inline-flex items-center gap-1 font-medium text-tier-block" data-testid="cost-cap-hit">
            <Icon name="block" className="text-[16px] leading-none" />
            Budget cap reached — LLM calls halted
          </span>
        ) : (
          <span className="text-on-surface-variant">{Math.round(ratio * 100)}% of daily cap</span>
        )}
        {!enforced && (
          <span className="text-on-surface-variant/70" data-testid="cost-unenforced">
            cap not enforced
          </span>
        )}
      </div>
    </div>
  );
}
