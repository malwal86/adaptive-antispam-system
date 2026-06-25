"use client";

/**
 * The scenario picker's placeholder. The scenario *runner* — the thing that drives a scripted
 * sequence like `sender_warms_up_then_attacks` into the live pipeline — is story 12.05; this story
 * (12.02) ships the other three controls, which reconfigure the running system today. Shown disabled
 * with the known scenario so the rail reads as intentional, not unfinished.
 */
export function ScenarioSection() {
  return (
    <section className="flex flex-col gap-2" data-testid="scenario-control">
      <h3 className="text-label-lg font-medium text-on-surface">Scenario</h3>
      <select
        data-testid="scenario-select"
        aria-label="Scenario"
        disabled
        defaultValue="sender_warms_up_then_attacks"
        className="w-full rounded-md border border-outline/60 bg-surface-container px-3 py-2 text-body-md text-on-surface-variant opacity-60"
      >
        <option value="sender_warms_up_then_attacks">sender_warms_up_then_attacks</option>
      </select>
      <p className="text-label-md text-on-surface-variant">
        The scenario runner arrives with story 12.05.
      </p>
    </section>
  );
}
