"use client";

import { useCallback, useEffect, useState } from "react";
import { Icon } from "@/components/ui/icon";
import { type ApplyStatus } from "@/components/lab/controls/ApplyStatusChip";
import { BudgetControl } from "@/components/lab/controls/BudgetControl";
import { PolicySelector } from "@/components/lab/controls/PolicySelector";
import { ScenarioSection } from "@/components/lab/controls/ScenarioSection";
import { ThresholdSliders } from "@/components/lab/controls/ThresholdSliders";
import {
  activatePolicy,
  applyThresholds,
  fetchBudget,
  fetchPolicies,
  thresholdsOf,
  updateBudget,
  type BudgetCaps,
  type PolicySummary,
  type Thresholds,
} from "@/lib/controls";

/** How long the "Applied" acknowledgement lingers before the chip clears. */
const ACK_MS = 2500;

/**
 * The left rail (story 12.02): scenario picker (its runner lands in 12.05), policy selector, threshold
 * sliders, and the LLM budget cap. The three live controls call the Java /controls API and reconfigure
 * the running system, so their effect shows up in the centre stream's subsequent decisions.
 */
export function ControlsRail() {
  const [policies, setPolicies] = useState<PolicySummary[] | null>(null);
  const [budget, setBudget] = useState<BudgetCaps | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [policyStatus, setPolicyStatus] = useState<ApplyStatus>("idle");
  const [thresholdStatus, setThresholdStatus] = useState<ApplyStatus>("idle");
  const [budgetStatus, setBudgetStatus] = useState<ApplyStatus>("idle");

  const load = useCallback(async () => {
    try {
      const [p, b] = await Promise.all([fetchPolicies(), fetchBudget()]);
      setPolicies(p);
      setBudget(b);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load controls");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const run = useCallback(
    async (
      setStatus: (s: ApplyStatus) => void,
      action: () => Promise<void>,
    ) => {
      setStatus("saving");
      try {
        await action();
        setStatus("applied");
        setTimeout(() => setStatus("idle"), ACK_MS);
      } catch (e) {
        setStatus("error");
        setError(e instanceof Error ? e.message : "Change failed");
      }
    },
    [],
  );

  const onActivate = (version: string) =>
    run(setPolicyStatus, async () => {
      await activatePolicy(version);
      setPolicies(await fetchPolicies());
    });

  const onApplyThresholds = (thresholds: Thresholds) =>
    run(setThresholdStatus, async () => {
      await applyThresholds(thresholds);
      setPolicies(await fetchPolicies());
    });

  const onApplyBudget = (dailyCapUsd: number, monthlyCapUsd: number) =>
    run(setBudgetStatus, async () => {
      setBudget(await updateBudget(dailyCapUsd, monthlyCapUsd));
    });

  const active = policies?.find((p) => p.active) ?? null;

  return (
    <aside
      data-testid="controls-rail"
      className="flex min-h-0 flex-col gap-5 overflow-y-auto rounded-lg border border-outline/50 bg-surface-container/40 p-4"
    >
      <div className="flex items-center gap-2 text-on-surface">
        <Icon name="tune" className="text-[20px] text-on-surface-variant" />
        <h2 className="text-title-sm font-medium">Controls</h2>
      </div>

      {error && (
        <p className="text-label-md text-tier-block" data-testid="controls-error">
          {error}
        </p>
      )}

      <ScenarioSection />

      {policies === null || budget === null ? (
        <p className="text-body-md text-on-surface-variant" data-testid="controls-loading">
          Loading controls…
        </p>
      ) : (
        <>
          <PolicySelector policies={policies} onActivate={onActivate} status={policyStatus} />
          {active && (
            <ThresholdSliders
              key={active.version}
              initial={thresholdsOf(active)}
              onApply={onApplyThresholds}
              status={thresholdStatus}
            />
          )}
          <BudgetControl
            key={`${budget.dailyCapUsd}-${budget.monthlyCapUsd}`}
            initial={budget}
            onApply={onApplyBudget}
            status={budgetStatus}
          />
        </>
      )}
    </aside>
  );
}
