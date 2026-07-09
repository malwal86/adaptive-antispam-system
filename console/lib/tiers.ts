import type { Tier } from "./api";

/** Presentation metadata for a decision tier: label, Material Symbol, and colour roles. */
export interface TierMeta {
  label: string;
  /** Material Symbols (Outlined) glyph name. */
  icon: string;
  /** One-line gloss of what the tier does to the message. */
  blurb: string;
  /** Tailwind classes for the accent (text/border) and the soft container fill. */
  accentText: string;
  accentBorder: string;
  containerBg: string;
  ring: string;
}

export const TIERS: Record<Tier, TierMeta> = {
  allow: {
    label: "Ham",
    icon: "check_circle",
    blurb: "Classified ham — delivered to the inbox",
    accentText: "text-tier-allow",
    accentBorder: "border-tier-allow/50",
    containerBg: "bg-tier-allow-container",
    ring: "ring-tier-allow/40",
  },
  warn: {
    label: "Warn",
    icon: "warning",
    blurb: "Delivered with a caution banner",
    accentText: "text-tier-warn",
    accentBorder: "border-tier-warn/50",
    containerBg: "bg-tier-warn-container",
    ring: "ring-tier-warn/40",
  },
  quarantine: {
    label: "Quarantine",
    icon: "inventory_2",
    blurb: "Held for review, kept out of the inbox",
    accentText: "text-tier-quarantine",
    accentBorder: "border-tier-quarantine/50",
    containerBg: "bg-tier-quarantine-container",
    ring: "ring-tier-quarantine/40",
  },
  block: {
    label: "Spam",
    icon: "block",
    blurb: "Classified spam — rejected outright",
    accentText: "text-tier-block",
    accentBorder: "border-tier-block/50",
    containerBg: "bg-tier-block-container",
    ring: "ring-tier-block/40",
  },
};

/** Reason-code → human label for the chips (mirrors AnalysisExplainer's vocabulary). */
export const REASON_LABELS: Record<string, string> = {
  KNOWN_BAD_URL: "Known-bad URL",
  MALFORMED_AUTH_BRAND_SPOOF: "Brand spoof · failed DMARC",
};

export function reasonLabel(code: string): string {
  return REASON_LABELS[code] ?? code.replaceAll("_", " ").toLowerCase();
}

/** Colour accent for a seed sample's ground-truth label (picker chips). */
export const LABEL_ACCENT: Record<SeedLabel, string> = {
  ham: "text-tier-allow",
  spam: "text-tier-warn",
  phish: "text-tier-block",
};

export type SeedLabel = "ham" | "spam" | "phish";
