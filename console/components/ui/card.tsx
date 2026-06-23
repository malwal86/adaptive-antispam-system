import * as React from "react";
import { cn } from "@/lib/utils";

// M3 surface card: container colour + soft elevation (light-from-above shadow).
const Card = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn(
        "rounded-lg border border-outline/60 bg-surface-container shadow-lg shadow-black/20",
        className,
      )}
      {...props}
    />
  ),
);
Card.displayName = "Card";

export { Card };
