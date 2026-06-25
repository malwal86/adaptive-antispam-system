import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ThresholdSliders } from "@/components/lab/controls/ThresholdSliders";
import type { Thresholds } from "@/lib/controls";

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

describe("ThresholdSliders", () => {
  it("seeds sliders from the initial thresholds", () => {
    render(<ThresholdSliders initial={ladder()} onApply={vi.fn()} status="idle" />);
    expect(screen.getByTestId("warn-slider")).toHaveValue("0.3");
    expect(screen.getByTestId("block-slider")).toHaveValue("0.85");
  });

  it("emits the edited thresholds on apply", () => {
    const onApply = vi.fn();
    render(<ThresholdSliders initial={ladder()} onApply={onApply} status="idle" />);

    fireEvent.change(screen.getByTestId("warn-slider"), { target: { value: "0.2" } });
    fireEvent.click(screen.getByTestId("apply-thresholds"));

    expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ warnThreshold: 0.2 }));
  });

  it("blocks apply and warns when the ladder is out of order", () => {
    const onApply = vi.fn();
    render(<ThresholdSliders initial={ladder()} onApply={onApply} status="idle" />);

    // Push warn above quarantine → invalid ladder.
    fireEvent.change(screen.getByTestId("warn-slider"), { target: { value: "0.9" } });

    expect(screen.getByTestId("threshold-invalid")).toBeInTheDocument();
    expect(screen.getByTestId("apply-thresholds")).toBeDisabled();
    fireEvent.click(screen.getByTestId("apply-thresholds"));
    expect(onApply).not.toHaveBeenCalled();
  });

  it("acknowledges an applied change", () => {
    render(<ThresholdSliders initial={ladder()} onApply={vi.fn()} status="applied" />);
    expect(screen.getByTestId("apply-status")).toHaveAttribute("data-status", "applied");
  });
});
