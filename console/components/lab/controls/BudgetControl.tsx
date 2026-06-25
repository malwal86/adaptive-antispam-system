"use client";

import { useState } from "react";
import { ApplyStatusChip, type ApplyStatus } from "@/components/lab/controls/ApplyStatusChip";
import { Slider } from "@/components/lab/controls/Slider";
import type { BudgetCaps } from "@/lib/controls";

interface BudgetControlProps {
  /** Starting caps (the in-force budget). Remount via key to reset. */
  initial: BudgetCaps;
  onApply(dailyCapUsd: number, monthlyCapUsd: number): void;
  status: ApplyStatus;
}

const usd = (v: number) => `$${v.toFixed(2)}`;

/**
 * The LLM spend-cap sliders (story 05.04 cap, adjusted live). Lowering the daily cap tightens the
 * budget the next LLM-routed decision reserves against. The daily cap is bounded by the monthly cap,
 * the same invariant the backend enforces.
 */
export function BudgetControl({ initial, onApply, status }: BudgetControlProps) {
  const [monthly, setMonthly] = useState(initial.monthlyCapUsd);
  // Keep daily ≤ monthly as the monthly cap moves.
  const [daily, setDaily] = useState(Math.min(initial.dailyCapUsd, initial.monthlyCapUsd));

  const onMonthly = (v: number) => {
    setMonthly(v);
    if (daily > v) {
      setDaily(v);
    }
  };

  return (
    <section className="flex flex-col gap-3" data-testid="budget-control">
      <div className="flex items-center justify-between">
        <h3 className="text-label-lg font-medium text-on-surface">LLM budget</h3>
        <ApplyStatusChip status={status} />
      </div>

      {!initial.enabled && (
        <p className="text-label-md text-on-surface-variant" data-testid="budget-disabled-note">
          Budget gating is off in this environment; the cap takes effect where the LLM is enabled.
        </p>
      )}

      <Slider label="Daily cap" value={daily} min={0} max={monthly} step={0.05}
        format={usd} data-testid="daily-slider"
        onChange={(v) => setDaily(Math.min(v, monthly))} />
      <Slider label="Monthly cap" value={monthly} min={0} max={100} step={0.5}
        format={usd} data-testid="monthly-slider" onChange={onMonthly} />

      <button
        type="button"
        data-testid="apply-budget"
        disabled={status === "saving"}
        onClick={() => onApply(daily, monthly)}
        className="self-start rounded-full bg-primary px-4 py-1.5 text-label-lg text-on-primary transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        Apply budget
      </button>
    </section>
  );
}
