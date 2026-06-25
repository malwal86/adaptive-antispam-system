"use client";

interface SliderProps {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange(value: number): void;
  /** Renders the current value (e.g. as a percentage or dollar amount). */
  format?(value: number): string;
  disabled?: boolean;
  "data-testid"?: string;
}

/** A labelled range control showing its current value; the building block of the sliders. */
export function Slider({
  label,
  value,
  min,
  max,
  step,
  onChange,
  format,
  disabled,
  ...rest
}: SliderProps) {
  return (
    <label className="flex flex-col gap-1">
      <span className="flex items-center justify-between text-label-md text-on-surface-variant">
        <span>{label}</span>
        <span className="tabular-nums text-on-surface" data-testid={`${rest["data-testid"]}-value`}>
          {format ? format(value) : value}
        </span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        data-testid={rest["data-testid"]}
        className="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-surface-variant accent-primary disabled:cursor-not-allowed disabled:opacity-50"
      />
    </label>
  );
}
