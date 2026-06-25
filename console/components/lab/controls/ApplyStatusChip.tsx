"use client";

import { Icon } from "@/components/ui/icon";
import { cn } from "@/lib/utils";

export type ApplyStatus = "idle" | "saving" | "applied" | "error";

const META: Record<Exclude<ApplyStatus, "idle">, { label: string; icon: string; tone: string }> = {
  saving: { label: "Applying", icon: "sync", tone: "text-on-surface-variant" },
  applied: { label: "Applied", icon: "check_circle", tone: "text-tier-allow" },
  error: { label: "Failed", icon: "error", tone: "text-tier-block" },
};

/** Acknowledges a control change in the UI (story 12.02 AC: changes are acknowledged). */
export function ApplyStatusChip({ status }: { status: ApplyStatus }) {
  if (status === "idle") {
    return null;
  }
  const meta = META[status];
  return (
    <span
      data-testid="apply-status"
      data-status={status}
      className={cn("inline-flex items-center gap-1 text-label-md", meta.tone)}
    >
      <Icon name={meta.icon} className={cn("text-[16px] leading-none", status === "saving" && "animate-pulse-soft")} />
      {meta.label}
    </span>
  );
}
