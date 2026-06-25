"use client";

import Link from "next/link";
import { Icon } from "@/components/ui/icon";
import { ControlsRail } from "@/components/lab/controls/ControlsRail";
import { LiveStream } from "@/components/lab/LiveStream";
import { RailPanel } from "@/components/lab/RailPanel";
import { useDecisionStream } from "@/lib/useDecisionStream";

/**
 * The Abuse Lab Console shell (story 12.01): a three-pane layout — controls,
 * the live decision stream, the story panel — over the Java API's SSE feed.
 *
 * <p>This story delivers the shell + transport: the centre pane renders decisions
 * live as the pipeline makes them, and the rails are present and responsive,
 * ready for the controls (12.02) and story panel (12.04). The console is a thin
 * client — it subscribes and renders; it never decides.
 */
export function LabConsole() {
  const { items, status } = useDecisionStream();

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between gap-4 border-b border-outline/40 px-6 py-4">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-md bg-primary/15 text-primary">
            <Icon name="biotech" filled className="text-[24px]" />
          </span>
          <div>
            <h1 className="text-title-lg font-medium tracking-tight">Abuse Lab</h1>
            <p className="text-label-md text-on-surface-variant">
              Live decision stream · Living Anti-Spam System
            </p>
          </div>
        </div>
        <Link
          href="/analyzer"
          className="inline-flex items-center gap-1.5 rounded-full border border-outline/60 bg-surface-container px-3 py-1.5 text-label-lg text-on-surface transition-colors hover:bg-surface-variant"
        >
          <Icon name="biotech" className="text-[18px]" />
          Single-email analyzer
        </Link>
      </header>

      <main className="grid min-h-0 flex-1 grid-cols-1 gap-4 p-4 lg:grid-cols-[280px_minmax(0,1fr)_340px]">
        <ControlsRail />

        <div className="flex min-h-0 flex-col rounded-lg border border-outline/50 bg-surface-container/40 p-4">
          <LiveStream items={items} status={status} />
        </div>

        <RailPanel
          data-testid="right-rail"
          title="Story"
          icon="insights"
          upcoming="Reputation curve, route mix, cost meter, and the baseline-miss table arrive in story 12.04."
        />
      </main>
    </div>
  );
}
