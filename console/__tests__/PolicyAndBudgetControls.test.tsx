import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { PolicySelector } from "@/components/lab/controls/PolicySelector";
import { BudgetControl } from "@/components/lab/controls/BudgetControl";
import type { BudgetCaps, PolicySummary } from "@/lib/controls";

function policy(version: string, active: boolean): PolicySummary {
  return {
    version,
    active,
    warnThreshold: 0.3,
    quarantineThreshold: 0.6,
    blockThreshold: 0.85,
    llmThreshold: 0.5,
    routingBandWidth: 0.05,
    burstThreshold: 5,
    modelVersion: "model-v1",
    createdAt: "2026-06-01T00:00:00Z",
  };
}

describe("PolicySelector", () => {
  it("shows the active policy as selected and activates on change", () => {
    const onActivate = vi.fn();
    render(
      <PolicySelector
        policies={[policy("a", false), policy("b", true)]}
        onActivate={onActivate}
        status="idle"
      />,
    );

    const select = screen.getByTestId("policy-select") as HTMLSelectElement;
    expect(select.value).toBe("b");

    fireEvent.change(select, { target: { value: "a" } });
    expect(onActivate).toHaveBeenCalledWith("a");
  });
});

describe("BudgetControl", () => {
  const caps = (overrides: Partial<BudgetCaps> = {}): BudgetCaps => ({
    enabled: true,
    dailyCapUsd: 0.5,
    monthlyCapUsd: 5,
    ...overrides,
  });

  it("emits the caps on apply", () => {
    const onApply = vi.fn();
    render(<BudgetControl initial={caps()} onApply={onApply} status="idle" />);

    fireEvent.change(screen.getByTestId("daily-slider"), { target: { value: "0.25" } });
    fireEvent.click(screen.getByTestId("apply-budget"));

    expect(onApply).toHaveBeenCalledWith(0.25, 5);
  });

  it("keeps the daily cap from exceeding the monthly cap", () => {
    const onApply = vi.fn();
    render(<BudgetControl initial={caps({ dailyCapUsd: 4, monthlyCapUsd: 5 })} onApply={onApply} status="idle" />);

    // Lower the monthly cap below the daily cap; daily should follow it down.
    fireEvent.change(screen.getByTestId("monthly-slider"), { target: { value: "2" } });
    fireEvent.click(screen.getByTestId("apply-budget"));

    expect(onApply).toHaveBeenCalledWith(2, 2);
  });

  it("notes when budget gating is disabled in this environment", () => {
    render(<BudgetControl initial={caps({ enabled: false })} onApply={vi.fn()} status="idle" />);
    expect(screen.getByTestId("budget-disabled-note")).toBeInTheDocument();
  });
});
