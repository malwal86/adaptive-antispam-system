"use client";

import { ApplyStatusChip, type ApplyStatus } from "@/components/lab/controls/ApplyStatusChip";
import type { PolicySummary } from "@/lib/controls";

interface PolicySelectorProps {
  policies: PolicySummary[];
  onActivate(version: string): void;
  status: ApplyStatus;
}

/** Switches the enforcing policy regime; the change applies to subsequent decisions (ties to 04.05). */
export function PolicySelector({ policies, onActivate, status }: PolicySelectorProps) {
  const active = policies.find((p) => p.active);

  return (
    <section className="flex flex-col gap-2" data-testid="policy-control">
      <div className="flex items-center justify-between">
        <h3 className="text-label-lg font-medium text-on-surface">Policy</h3>
        <ApplyStatusChip status={status} />
      </div>
      <select
        data-testid="policy-select"
        aria-label="Active policy"
        value={active?.version ?? ""}
        disabled={status === "saving" || policies.length === 0}
        onChange={(e) => onActivate(e.target.value)}
        className="w-full rounded-md border border-outline/60 bg-surface-container px-3 py-2 text-body-md text-on-surface disabled:opacity-50"
      >
        {policies.length === 0 && <option value="">No policies</option>}
        {policies.map((policy) => (
          <option key={policy.version} value={policy.version}>
            {policy.version}
            {policy.active ? " · active" : ""}
          </option>
        ))}
      </select>
    </section>
  );
}
