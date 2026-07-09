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

/** Which folder a decision lands the mail in — the metaphor every viewer already owns. */
export type Folder = "inbox" | "spam";

export interface Outcome {
  /** Where the mail ends up. */
  folder: Folder;
  /** A plain, past-tense verb phrase for what happened, e.g. "Delivered to inbox". */
  verb: string;
  /** Material Symbols glyph for the outcome. */
  icon: string;
}

/**
 * The Inbox-vs-Spam framing for a tier. `allow`/`warn` are delivered (inbox); `quarantine`/`block`
 * are withheld (spam). The optional `delivered` flag from the backend wins when present.
 */
export function outcomeFor(tier: Tier, delivered?: boolean): Outcome {
  const inInbox = delivered ?? (tier === "allow" || tier === "warn");
  if (!inInbox) {
    return tier === "quarantine"
      ? { folder: "spam", verb: "Held for review", icon: "inventory_2" }
      : { folder: "spam", verb: "Moved to spam", icon: "block" };
  }
  return tier === "warn"
    ? { folder: "inbox", verb: "Delivered — with caution", icon: "warning" }
    : { folder: "inbox", verb: "Delivered to inbox", icon: "check_circle" };
}

/**
 * Plain-English, non-technical phrasing of why a decision was made — friendlier than the analyzer's
 * grounded sentence, for a lay viewer. Keyed off the first reason code; falls back to the backend's
 * own explanation, then a folder-appropriate default.
 */
export const FRIENDLY_REASON: Record<string, string> = {
  KNOWN_BAD_URL: "The link points to a known scam site.",
  MALFORMED_AUTH_BRAND_SPOOF: "Pretends to be a trusted brand, but fails verification.",
  SUSPICIOUS_LINK: "A link shows classic phishing signs.",
  CREDENTIAL_PHISHING: "Tries to get your password or account details.",
  URGENCY_PRESSURE: "Uses fake urgency to rush you into acting.",
  PRIZE_OR_LOTTERY_BAIT: "Dangles a prize or winnings to bait you.",
  UNSOLICITED_BULK: "Unsolicited bulk mail you never signed up for.",
  SENDER_REPUTATION_RISK: "This sender isn't trusted yet.",
  BENIGN_CONTENT: "Looks safe — nothing suspicious found.",
  BURST_OVERRIDE: "Part of a sudden flood of similar messages.",
};

export function plainReason(
  reasonCodes: string[],
  folder: Folder,
  explanation?: string,
): string {
  const first = reasonCodes[0];
  if (first && FRIENDLY_REASON[first]) {
    return FRIENDLY_REASON[first];
  }
  if (explanation && explanation.trim().length > 0) {
    return explanation;
  }
  return folder === "inbox" ? "Looks safe — nothing suspicious found." : "Flagged as suspicious.";
}

/** Colour accent for a seed sample's ground-truth label (picker chips). */
export const LABEL_ACCENT: Record<SeedLabel, string> = {
  ham: "text-tier-allow",
  spam: "text-tier-warn",
  phish: "text-tier-block",
};

export type SeedLabel = "ham" | "spam" | "phish";
