import * as React from "react";
import { cn } from "@/lib/utils";

export type BadgeProps = React.HTMLAttributes<HTMLSpanElement>;

/** A pill — the base for reason chips and metadata tags. */
const Badge = React.forwardRef<HTMLSpanElement, BadgeProps>(({ className, ...props }, ref) => (
  <span
    ref={ref}
    className={cn(
      "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-label-md font-medium",
      className,
    )}
    {...props}
  />
));
Badge.displayName = "Badge";

export { Badge };
