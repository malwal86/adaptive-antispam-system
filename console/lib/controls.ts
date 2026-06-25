// Typed client for the left-rail controls (story 12.02). Thin over the Java
// /controls API: each write actually reconfigures the running system (active
// policy, thresholds, LLM budget caps), and the effect shows up in the live
// decision stream — the console never decides anything itself.

import { API_BASE_URL, errorMessage } from "./api";

export interface PolicySummary {
  version: string;
  active: boolean;
  warnThreshold: number;
  quarantineThreshold: number;
  blockThreshold: number;
  llmThreshold: number;
  routingBandWidth: number;
  burstThreshold: number;
  modelVersion: string;
  createdAt: string;
}

/** The slider-bound tunables applied as one new policy version. */
export interface Thresholds {
  warnThreshold: number;
  quarantineThreshold: number;
  blockThreshold: number;
  llmThreshold: number;
  routingBandWidth: number;
}

export interface BudgetCaps {
  enabled: boolean;
  dailyCapUsd: number;
  monthlyCapUsd: number;
}

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw new Error(await errorMessage(res));
  }
  return (await res.json()) as T;
}

export async function fetchPolicies(): Promise<PolicySummary[]> {
  return readJson(await fetch(`${API_BASE_URL}/controls/policies`));
}

export async function activatePolicy(version: string): Promise<PolicySummary> {
  return readJson(
    await fetch(`${API_BASE_URL}/controls/policies/${encodeURIComponent(version)}/activate`, {
      method: "POST",
    }),
  );
}

export async function applyThresholds(thresholds: Thresholds): Promise<PolicySummary> {
  return readJson(
    await fetch(`${API_BASE_URL}/controls/thresholds`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(thresholds),
    }),
  );
}

export async function fetchBudget(): Promise<BudgetCaps> {
  return readJson(await fetch(`${API_BASE_URL}/controls/budget`));
}

export async function updateBudget(dailyCapUsd: number, monthlyCapUsd: number): Promise<BudgetCaps> {
  return readJson(
    await fetch(`${API_BASE_URL}/controls/budget`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ dailyCapUsd, monthlyCapUsd }),
    }),
  );
}

/** True iff the tier thresholds form the required non-decreasing ladder in [0,1]. */
export function isMonotonicLadder(t: Thresholds): boolean {
  const inUnit = [t.warnThreshold, t.quarantineThreshold, t.blockThreshold].every(
    (v) => v >= 0 && v <= 1,
  );
  return inUnit && t.warnThreshold <= t.quarantineThreshold && t.quarantineThreshold <= t.blockThreshold;
}

/** Picks the slider-bound thresholds out of a policy summary. */
export function thresholdsOf(policy: PolicySummary): Thresholds {
  return {
    warnThreshold: policy.warnThreshold,
    quarantineThreshold: policy.quarantineThreshold,
    blockThreshold: policy.blockThreshold,
    llmThreshold: policy.llmThreshold,
    routingBandWidth: policy.routingBandWidth,
  };
}
