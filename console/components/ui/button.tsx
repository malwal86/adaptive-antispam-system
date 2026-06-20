import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

// shadcn/ui-style button, M3-flavoured: filled primary, tonal, and text variants.
const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-label-lg font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/60 disabled:pointer-events-none disabled:opacity-50 select-none",
  {
    variants: {
      variant: {
        filled: "bg-primary text-on-primary hover:bg-primary/90 active:bg-primary/80",
        tonal:
          "bg-surface-variant text-on-surface hover:bg-surface-variant/80 active:bg-surface-variant/70",
        outline:
          "border border-outline text-on-surface hover:bg-surface-variant/40 active:bg-surface-variant/60",
        ghost: "text-on-surface-variant hover:bg-surface-variant/40 hover:text-on-surface",
      },
      size: {
        md: "h-11 px-6",
        sm: "h-9 px-4 text-label-md",
        icon: "h-10 w-10",
      },
    },
    defaultVariants: { variant: "filled", size: "md" },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => (
    <button ref={ref} className={cn(buttonVariants({ variant, size, className }))} {...props} />
  ),
);
Button.displayName = "Button";

export { Button, buttonVariants };
