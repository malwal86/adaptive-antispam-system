"use client";

import { useState } from "react";
import { Icon } from "@/components/ui/icon";
import { ApplyStatusChip, type ApplyStatus } from "@/components/lab/controls/ApplyStatusChip";

/**
 * The scenario trigger (story 12.05): the single control action that runs a scripted scenario
 * end-to-end. Picking a scenario and pressing Run posts to the Java runner, which injects the
 * scenario's emails into the live pipeline — so the centre stream and story panel animate the beats
 * from real backend decisions, not a scripted animation.
 *
 * <p>Scenarios are shown by a plain-language title and a one-line description so it's clear what each
 * one demonstrates; the runner is addressed by the stable id underneath. It owns no logic of its own:
 * like the other rail controls it reports the selected scenario id up to {@code ControlsRail} via
 * {@code onStart} and renders the shared apply-status acknowledgement. The ids here mirror the Java
 * scenario catalog (the runner rejects an unknown one with a 400).
 */
const SCENARIOS = [
  {
    id: "sender_warms_up_then_attacks",
    title: "A trusted sender turns hostile",
    description:
      "A sender earns trust with normal, authenticated mail — then that same account is compromised and blasts phishing. Watch the trust curve climb, then collapse as the attack is caught and blocked.",
  },
  {
    id: "a_normal_morning",
    title: "A normal morning",
    description:
      "A routine inbox: a couple of genuine emails and a newsletter sail through while two obvious scams are stopped. The everyday split — proof the system isn't trigger-happy.",
  },
] as const;

export function ScenarioSection({
  onStart,
  status,
}: {
  onStart: (scenario: string) => void;
  status: ApplyStatus;
}) {
  const [selected, setSelected] = useState<string>(SCENARIOS[0].id);
  const running = status === "saving";
  const description =
    SCENARIOS.find((s) => s.id === selected)?.description ?? "";

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
        {SCENARIOS.map((s) => (
          <option key={s.id} value={s.id}>
            {s.title}
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

      <p className="text-label-md text-on-surface-variant">{description}</p>
    </section>
  );
}
