import Link from "next/link";
import type { Metadata } from "next";
import { Analyzer } from "@/components/Analyzer";
import { Icon } from "@/components/ui/icon";
import { TIERS } from "@/lib/tiers";
import type { Tier } from "@/lib/api";

export const metadata: Metadata = {
  title: "Spam Classifier: Single-Email Analyzer",
  description:
    "Paste or pick an email and see the Living Anti-Spam System's decision: tier, reason codes, route, and latency.",
};

const TIER_ORDER: Tier[] = ["allow", "warn", "quarantine", "block"];

export default function AnalyzerPage() {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-6xl flex-col gap-10 px-6 py-10">
      <header className="flex flex-col gap-4">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <span className="grid h-11 w-11 place-items-center rounded-md bg-primary/15 text-primary">
              <Icon name="shield" filled className="text-[26px]" />
            </span>
            <div>
              <h1 className="text-display-sm font-medium tracking-tight">Spam Classifier</h1>
              <p className="text-body-md text-on-surface-variant">
                Single-email analyzer · Living Anti-Spam System
              </p>
            </div>
          </div>
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 rounded-full border border-outline/60 bg-surface-container px-3 py-1.5 text-label-lg text-on-surface transition-colors hover:bg-surface-variant"
          >
            <Icon name="sensors" className="text-[18px]" />
            Live console
          </Link>
        </div>

        {/* Tier legend — the shared decision vocabulary, colour-coded. */}
        <div className="flex flex-wrap gap-2">
          {TIER_ORDER.map((tier) => {
            const meta = TIERS[tier];
            return (
              <span
                key={tier}
                className={`inline-flex items-center gap-1.5 rounded-full border ${meta.accentBorder} ${meta.containerBg} ${meta.accentText} px-3 py-1 text-label-md`}
              >
                <Icon name={meta.icon} className="text-[16px] leading-none" />
                {meta.label}
              </span>
            );
          })}
        </div>
      </header>

      <Analyzer />

      <footer className="mt-auto border-t border-outline/40 pt-4 text-label-md text-on-surface-variant">
        Decisions come from the Java pipeline (hard rules now; calibrated model + LLM fallback in
        later epics). This console is a thin client: it renders verdicts, it doesn&apos;t make them.
      </footer>
    </main>
  );
}
