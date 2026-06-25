"use client";

import { useState } from "react";
import { ApplyStatusChip, type ApplyStatus } from "@/components/lab/controls/ApplyStatusChip";
import { Slider } from "@/components/lab/controls/Slider";
import { isMonotonicLadder, type Thresholds } from "@/lib/controls";

interface ThresholdSlidersProps {
  /** Starting values (the active policy's thresholds). Remount via key to reset. */
  initial: Thresholds;
  onApply(thresholds: Thresholds): void;
  status: ApplyStatus;
}

const pct = (v: number) => `${Math.round(v * 100)}%`;

/**
 * Tier and routing threshold sliders. Applying them mints and activates a new policy version, so the
 * new cut-points govern subsequent decisions. Apply is blocked unless the tier thresholds form the
 * required non-decreasing ladder — the same invariant the backend enforces.
 */
export function ThresholdSliders({ initial, onApply, status }: ThresholdSlidersProps) {
  const [draft, setDraft] = useState<Thresholds>(initial);
  const set = (patch: Partial<Thresholds>) => setDraft((d) => ({ ...d, ...patch }));
  const valid = isMonotonicLadder(draft);

  return (
    <section className="flex flex-col gap-3" data-testid="threshold-control">
      <div className="flex items-center justify-between">
        <h3 className="text-label-lg font-medium text-on-surface">Thresholds</h3>
        <ApplyStatusChip status={status} />
      </div>

      <Slider label="Warn" value={draft.warnThreshold} min={0} max={1} step={0.01}
        format={pct} data-testid="warn-slider"
        onChange={(v) => set({ warnThreshold: v })} />
      <Slider label="Quarantine" value={draft.quarantineThreshold} min={0} max={1} step={0.01}
        format={pct} data-testid="quarantine-slider"
        onChange={(v) => set({ quarantineThreshold: v })} />
      <Slider label="Block" value={draft.blockThreshold} min={0} max={1} step={0.01}
        format={pct} data-testid="block-slider"
        onChange={(v) => set({ blockThreshold: v })} />
      <Slider label="LLM-routing floor" value={draft.llmThreshold} min={0} max={1} step={0.01}
        format={pct} data-testid="llm-slider"
        onChange={(v) => set({ llmThreshold: v })} />
      <Slider label="Routing band" value={draft.routingBandWidth} min={0} max={0.5} step={0.01}
        format={pct} data-testid="band-slider"
        onChange={(v) => set({ routingBandWidth: v })} />

      {!valid && (
        <p className="text-label-md text-tier-block" data-testid="threshold-invalid">
          Thresholds must rise: warn ≤ quarantine ≤ block.
        </p>
      )}

      <button
        type="button"
        data-testid="apply-thresholds"
        disabled={!valid || status === "saving"}
        onClick={() => onApply(draft)}
        className="self-start rounded-full bg-primary px-4 py-1.5 text-label-lg text-on-primary transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        Apply thresholds
      </button>
    </section>
  );
}
