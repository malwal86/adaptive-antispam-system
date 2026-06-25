import { afterEach, describe, expect, it, vi } from "vitest";
import {
  activatePolicy,
  applyThresholds,
  fetchPolicies,
  isMonotonicLadder,
  thresholdsOf,
  updateBudget,
  type PolicySummary,
  type Thresholds,
} from "@/lib/controls";

function policy(overrides: Partial<PolicySummary> = {}): PolicySummary {
  return {
    version: "bootstrap-v1",
    active: true,
    warnThreshold: 0.3,
    quarantineThreshold: 0.6,
    blockThreshold: 0.85,
    llmThreshold: 0.5,
    routingBandWidth: 0.05,
    burstThreshold: 5,
    modelVersion: "model-v1",
    createdAt: "2026-06-01T00:00:00Z",
    ...overrides,
  };
}

function ladder(overrides: Partial<Thresholds> = {}): Thresholds {
  return {
    warnThreshold: 0.3,
    quarantineThreshold: 0.6,
    blockThreshold: 0.85,
    llmThreshold: 0.5,
    routingBandWidth: 0.05,
    ...overrides,
  };
}

function mockFetch(json: unknown, ok = true) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok,
    status: ok ? 200 : 400,
    statusText: ok ? "OK" : "Bad Request",
    json: async () => json,
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

describe("isMonotonicLadder", () => {
  it("accepts a non-decreasing ladder in [0,1]", () => {
    expect(isMonotonicLadder(ladder())).toBe(true);
    expect(isMonotonicLadder(ladder({ warnThreshold: 0.6, quarantineThreshold: 0.6 }))).toBe(true);
  });

  it("rejects an out-of-order ladder", () => {
    expect(isMonotonicLadder(ladder({ warnThreshold: 0.7, quarantineThreshold: 0.3 }))).toBe(false);
  });

  it("rejects out-of-range values", () => {
    expect(isMonotonicLadder(ladder({ blockThreshold: 1.4 }))).toBe(false);
  });
});

describe("thresholdsOf", () => {
  it("extracts only the slider-bound tunables", () => {
    expect(thresholdsOf(policy())).toEqual(ladder());
  });
});

describe("controls client", () => {
  it("fetchPolicies GETs the policies endpoint", async () => {
    const f = mockFetch([policy()]);
    const result = await fetchPolicies();
    expect(result).toHaveLength(1);
    expect(f.mock.calls[0][0]).toContain("/controls/policies");
  });

  it("activatePolicy POSTs to the activate endpoint with an encoded version", async () => {
    const f = mockFetch(policy({ version: "v 2", active: true }));
    await activatePolicy("v 2");
    const [url, init] = f.mock.calls[0];
    expect(url).toContain("/controls/policies/v%202/activate");
    expect(init).toMatchObject({ method: "POST" });
  });

  it("applyThresholds POSTs the threshold payload as JSON", async () => {
    const f = mockFetch(policy());
    await applyThresholds(ladder({ warnThreshold: 0.2 }));
    const [url, init] = f.mock.calls[0];
    expect(url).toContain("/controls/thresholds");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toMatchObject({ warnThreshold: 0.2 });
  });

  it("updateBudget POSTs the caps", async () => {
    const f = mockFetch({ enabled: true, dailyCapUsd: 0.2, monthlyCapUsd: 4 });
    const result = await updateBudget(0.2, 4);
    expect(result.dailyCapUsd).toBe(0.2);
    expect(JSON.parse(f.mock.calls[0][1].body as string)).toEqual({
      dailyCapUsd: 0.2,
      monthlyCapUsd: 4,
    });
  });

  it("throws on a non-ok response", async () => {
    mockFetch({ error: "boom" }, false);
    await expect(fetchPolicies()).rejects.toThrow("boom");
  });
});
