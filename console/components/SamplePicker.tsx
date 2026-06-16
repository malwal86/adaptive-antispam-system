"use client";

import { Icon } from "@/components/ui/icon";
import type { SeedSample } from "@/lib/api";
import { LABEL_ACCENT } from "@/lib/tiers";
import { cn } from "@/lib/utils";

/**
 * The seed-sample picker: pick a known ham/spam/phish from the labeled corpus and
 * analyse it by id — a decision with zero pasting. Empty/error states are handled
 * by the parent; this just renders the chips.
 */
export function SamplePicker({
  samples,
  onPick,
  disabled,
  busyId,
}: {
  samples: SeedSample[];
  onPick: (sample: SeedSample) => void;
  disabled?: boolean;
  busyId?: string | null;
}) {
  if (samples.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-col gap-2" data-testid="sample-picker">
      <span className="text-label-md uppercase tracking-wide text-on-surface-variant">
        Or try a labeled sample
      </span>
      <div className="flex flex-wrap gap-2">
        {samples.map((sample) => (
          <button
            key={sample.emailId}
            type="button"
            disabled={disabled}
            onClick={() => onPick(sample)}
            data-testid="sample-chip"
            data-label={sample.label}
            className={cn(
              "group inline-flex max-w-xs items-center gap-2 rounded-full border border-outline bg-surface-container px-3 py-1.5 text-label-md transition-colors hover:bg-surface-variant/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/60 disabled:opacity-50",
            )}
          >
            <Icon
              name={busyId === sample.emailId ? "progress_activity" : "mail"}
              className={cn(
                "text-[16px] leading-none",
                LABEL_ACCENT[sample.label],
                busyId === sample.emailId && "animate-spin",
              )}
            />
            <span className={cn("font-medium uppercase", LABEL_ACCENT[sample.label])}>
              {sample.label}
            </span>
            <span className="truncate text-on-surface-variant">
              {sample.subject?.trim() || sample.senderDomain || sample.emailId.slice(0, 8)}
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}
