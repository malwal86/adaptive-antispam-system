"use client";

import { Icon } from "@/components/ui/icon";
import type { BaselineMiss } from "@/lib/arena";
import { shortId } from "@/lib/format";
import { cn } from "@/lib/utils";

interface BaselineMissTableProps {
  misses: BaselineMiss[];
  loading: boolean;
  error: string | null;
}

/**
 * The "danger missed by baseline" table (story 12.04): runs where the fixed baseline defender and
 * the current model disagreed on how much abuse got through (story 08.04). A positive delta is the
 * headline — danger the baseline let through that the current model caught — shown in the block
 * accent; a negative delta (a regression) is shown plainly so the table never flatters the model.
 * Every row is a real measured bypass comparison; the console only renders it.
 */
export function BaselineMissTable({ misses, loading, error }: BaselineMissTableProps) {
  return (
    <div data-testid="baseline-miss-table">
      <p className="mb-1.5 text-label-md text-on-surface-variant">Scams an old-school filter would miss</p>

      {error ? (
        <p className="text-label-md text-tier-block" data-testid="baseline-miss-error">
          {error}
        </p>
      ) : loading ? (
        <p className="text-label-md text-on-surface-variant" data-testid="baseline-miss-loading">
          Loading arena runs…
        </p>
      ) : misses.length === 0 ? (
        <p className="text-label-md text-on-surface-variant" data-testid="baseline-miss-empty">
          No baseline disagreements recorded yet. Run the arena to populate this.
        </p>
      ) : (
        <ul className="flex flex-col gap-1.5">
          {misses.map((miss) => {
            const baselineWorse = miss.delta > 0;
            return (
              <li
                key={miss.runId}
                data-testid="baseline-miss-row"
                className="flex items-center justify-between gap-2 rounded-md border border-outline/40 bg-surface-container/50 px-2.5 py-1.5"
              >
                <span className="inline-flex items-center gap-1.5 text-label-md text-on-surface-variant">
                  <Icon name="science" className="text-[16px] leading-none" />
                  <span className="tabular-nums">{shortId(miss.runId)}</span>
                </span>
                <span className="flex items-center gap-2 tabular-nums text-label-md">
                  <span className="text-on-surface-variant">
                    base {formatPct(miss.baselineBypassRate)}
                  </span>
                  <span className="text-on-surface-variant">·</span>
                  <span className="text-on-surface">now {formatPct(miss.currentBypassRate)}</span>
                  <span
                    className={cn(
                      "inline-flex items-center gap-0.5 font-medium",
                      baselineWorse ? "text-tier-block" : "text-tier-warn",
                    )}
                  >
                    <Icon
                      name={baselineWorse ? "arrow_downward" : "arrow_upward"}
                      className="text-[14px] leading-none"
                    />
                    {formatPct(Math.abs(miss.delta))}
                  </span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function formatPct(rate: number): string {
  return `${Math.round(rate * 100)}%`;
}
