import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ScenarioSection } from "@/components/lab/controls/ScenarioSection";

describe("ScenarioSection", () => {
  it("starts the selected scenario when Run is pressed", () => {
    const onStart = vi.fn();
    render(<ScenarioSection onStart={onStart} status="idle" />);

    fireEvent.click(screen.getByTestId("scenario-start"));

    // The everyday-inbox scenario is the default — the one anyone can read at a glance.
    expect(onStart).toHaveBeenCalledWith("a_normal_morning");
  });

  it("disables the picker and button while a run is in flight", () => {
    render(<ScenarioSection onStart={vi.fn()} status="saving" />);

    expect(screen.getByTestId("scenario-select")).toBeDisabled();
    expect(screen.getByTestId("scenario-start")).toBeDisabled();
    expect(screen.getByTestId("scenario-start")).toHaveTextContent("Running");
  });

  it("acknowledges a started run via the shared apply-status chip", () => {
    render(<ScenarioSection onStart={vi.fn()} status="applied" />);
    expect(screen.getByTestId("apply-status")).toHaveAttribute("data-status", "applied");
  });
});
