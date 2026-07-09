"use client";

import { useEffect, useState } from "react";
import { Icon } from "@/components/ui/icon";
import { BaselineMissTable } from "@/components/lab/story/BaselineMissTable";
import { CostMeter } from "@/components/lab/story/CostMeter";
import { ReputationChart } from "@/components/lab/story/ReputationChart";
import { RouteMixBar } from "@/components/lab/story/RouteMixBar";
import { baselineMisses, fetchBypassTrend, type BaselineMiss } from "@/lib/arena";
import { fetchBudget, type BudgetCaps } from "@/lib/controls";
import type { StreamStats } from "@/lib/streamStats";

/**
 * The right story panel (story 12.04): the system's economics and adaptation read off real backend
 * signals, not individual verdicts. The reputation curve, route mix, and cost meter bind to the live
 * decision feed via {@link StreamStats}; the baseline-miss table reads the arena's bypass comparison
 * (story 08.04). The panel is a thin reader — it fetches the cap and the arena trend once and
 * renders; it never decides anything. The thunderclap scenario (story 12.05) drives all four at once.
 */
export function StoryPanel({ stats }: { stats: StreamStats }) {
  const [budget, setBudget] = useState<BudgetCaps | null>(null);
  const [misses, setMisses] = useState<BaselineMiss[]>([]);
  const [trendLoading, setTrendLoading] = useState(true);
  const [trendError, setTrendError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchBudget()
      .then((b) => {
        if (!cancelled) setBudget(b);
      })
      .catch(() => {
        // The cost meter still draws against a zero cap; a missing budget must not blank the panel.
      });
    void fetchBypassTrend()
      .then((trend) => {
        if (!cancelled) setMisses(baselineMisses(trend));
      })
      .catch((e: unknown) => {
        if (!cancelled) setTrendError(e instanceof Error ? e.message : "Failed to load arena runs");
      })
      .finally(() => {
        if (!cancelled) setTrendLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <aside
      data-testid="right-rail"
      className="flex min-h-0 flex-col gap-5 overflow-y-auto rounded-lg border border-outline/50 bg-surface-container/40 p-4"
    >
      <div className="flex items-center gap-2 text-on-surface">
        <Icon name="insights" className="text-[20px] text-on-surface-variant" />
        <h2 className="text-title-sm font-medium">Behind the scenes</h2>
      </div>

      <section aria-label="How much we trust the sender">
        <ReputationChart series={stats.trustSeries} />
      </section>

      <hr className="border-outline/30" />

      <section aria-label="How it decided">
        <RouteMixBar stats={stats} />
      </section>

      {/* The two most technical read-outs stay one tap away, so they don't dominate a lay viewer's
          first read of the panel. */}
      <details className="group rounded-md border border-outline/30 bg-surface/30 open:bg-surface/40">
        <summary className="flex cursor-pointer items-center gap-1.5 px-3 py-2 text-label-md text-on-surface-variant">
          <Icon name="expand_more" className="text-[16px] transition-transform group-open:rotate-180" />
          For the curious: cost &amp; benchmark
        </summary>
        <div className="flex flex-col gap-5 px-3 pb-3 pt-1">
          <section aria-label="LLM cost">
            <CostMeter
              costUsd={stats.costUsd}
              capUsd={budget?.dailyCapUsd ?? 0}
              enforced={budget?.enabled ?? false}
            />
          </section>

          <hr className="border-outline/30" />

          <section aria-label="Scams an old-school filter would miss">
            <BaselineMissTable misses={misses} loading={trendLoading} error={trendError} />
          </section>
        </div>
      </details>
    </aside>
  );
}
