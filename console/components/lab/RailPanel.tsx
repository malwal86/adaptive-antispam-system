import type { ReactNode } from "react";
import { Icon } from "@/components/ui/icon";

interface RailPanelProps {
  title: string;
  icon: string;
  /** What this rail will hold, shown until the owning story fills it in. */
  upcoming: string;
  "data-testid"?: string;
  children?: ReactNode;
}

/**
 * A side-rail container. In story 12.01 the rails are present and responsive but
 * empty; the controls (12.02) and story panel (12.04) fill them. The placeholder
 * states what is coming so the shell reads as intentional, not unfinished.
 */
export function RailPanel({ title, icon, upcoming, children, ...rest }: RailPanelProps) {
  return (
    <aside
      data-testid={rest["data-testid"]}
      className="flex min-h-0 flex-col gap-3 rounded-lg border border-outline/50 bg-surface-container/40 p-4"
    >
      <div className="flex items-center gap-2 text-on-surface">
        <Icon name={icon} className="text-[20px] text-on-surface-variant" />
        <h2 className="text-title-sm font-medium">{title}</h2>
      </div>
      {children ?? (
        <p className="text-body-md text-on-surface-variant">{upcoming}</p>
      )}
    </aside>
  );
}
