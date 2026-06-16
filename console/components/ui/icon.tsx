import { cn } from "@/lib/utils";

interface IconProps {
  /** Material Symbols (Outlined) glyph name, e.g. "check_circle". */
  name: string;
  /** Use the filled axis (reserved for active/selected states, per the guidelines). */
  filled?: boolean;
  className?: string;
  "aria-hidden"?: boolean;
}

/** A single Material Symbol. Colour comes from the surrounding text colour. */
export function Icon({ name, filled, className, ...rest }: IconProps) {
  return (
    <span
      className={cn("material-symbols-outlined", filled && "filled", className)}
      aria-hidden={rest["aria-hidden"] ?? true}
    >
      {name}
    </span>
  );
}
