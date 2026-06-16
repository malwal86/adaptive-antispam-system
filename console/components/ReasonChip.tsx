import { Badge } from "@/components/ui/badge";
import { Icon } from "@/components/ui/icon";
import { reasonLabel } from "@/lib/tiers";
import { cn } from "@/lib/utils";

/** A reason-code chip on the result card. Accent colour is inherited from the tier. */
export function ReasonChip({ code, className }: { code: string; className?: string }) {
  return (
    <Badge
      data-testid="reason-chip"
      className={cn("border-current/30 bg-surface/40", className)}
    >
      <Icon name="label_important" className="text-[16px] leading-none" />
      {reasonLabel(code)}
    </Badge>
  );
}
