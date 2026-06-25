"use client";

import { Icon } from "@/components/ui/icon";
import type { StreamStatus } from "@/lib/decisionStream";
import { cn } from "@/lib/utils";

interface StatusMeta {
  label: string;
  icon: string;
  tone: string;
  /** Slow pulse = indeterminate "working" (animation guidelines), used while not yet live. */
  pulse: boolean;
}

const STATUS: Record<StreamStatus, StatusMeta> = {
  connecting: { label: "Connecting", icon: "sync", tone: "text-on-surface-variant", pulse: true },
  open: { label: "Live", icon: "sensors", tone: "text-tier-allow", pulse: false },
  reconnecting: { label: "Reconnecting", icon: "sync_problem", tone: "text-tier-warn", pulse: true },
  closed: { label: "Disconnected", icon: "cloud_off", tone: "text-tier-block", pulse: false },
};

/** The live-connection indicator for the decision stream. */
export function StreamStatusPill({ status }: { status: StreamStatus }) {
  const meta = STATUS[status];
  return (
    <span
      data-testid="stream-status"
      data-status={status}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border border-outline/50 bg-surface-container px-3 py-1 text-label-md",
        meta.tone,
        meta.pulse && "animate-pulse-soft",
      )}
    >
      <Icon name={meta.icon} className="text-[16px] leading-none" />
      {meta.label}
    </span>
  );
}
