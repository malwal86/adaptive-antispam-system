"use client";

import { useState } from "react";
import { Icon } from "@/components/ui/icon";
import { ApplyStatusChip, type ApplyStatus } from "@/components/lab/controls/ApplyStatusChip";

/**
 * The scenario trigger (story 12.05): the single control action that runs the thunderclap end-to-end.
 * Picking a scenario and pressing Run posts to the Java runner, which injects a scripted warm-up →
 * mutated-attack sequence into the live pipeline — so the centre stream and story panel animate the
 * beats (reputation rise then collapse, the LLM engaging, the cost meter ticking, the shadow diff)
 * from real backend decisions, not a scripted animation.
 *
 * <p>It owns no logic of its own: like the other rail controls it reports the selected scenario up to
 * {@code ControlsRail} via {@code onStart} and renders the shared apply-status acknowledgement.
 */
// The scenarios the runner knows. Shown by their raw id — the lab reads as technical on purpose.
const SCENARIOS = ["sender_warms_up_then_attacks"] as const;

export function ScenarioSection({
  onStart,
  status,
}: {
  onStart: (scenario: string) => void;
  status: ApplyStatus;
}) {
  const [selected, setSelected] = useState<string>(SCENARIOS[0]);
  const running = status === "saving";

  return (
    <section className="flex flex-col gap-2" data-testid="scenario-control">
      <div className="flex items-center justify-between">
        <h3 className="text-label-lg font-medium text-on-surface">Scenario</h3>
        <ApplyStatusChip status={status} />
      </div>

      <select
        data-testid="scenario-select"
        aria-label="Scenario"
        value={selected}
        onChange={(e) => setSelected(e.target.value)}
        disabled={running}
        className="w-full rounded-md border border-outline/60 bg-surface-container px-3 py-2 text-body-md text-on-surface disabled:opacity-60"
      >
        {SCENARIOS.map((id) => (
          <option key={id} value={id}>
            {id}
          </option>
        ))}
      </select>

      <button
        type="button"
        data-testid="scenario-start"
        onClick={() => onStart(selected)}
        disabled={running}
        className="inline-flex items-center justify-center gap-1.5 rounded-md bg-primary px-3 py-2 text-label-lg font-medium text-on-primary transition-colors hover:bg-primary/90 disabled:opacity-60"
      >
        <Icon name="bolt" className="text-[18px] leading-none" />
        {running ? "Running…" : "Run scenario"}
      </button>

      <p className="text-label-md text-on-surface-variant">
        Drives the full thunderclap through the live pipeline — watch the curve collapse and the LLM engage.
      </p>
    </section>
  );
}
