"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Icon } from "@/components/ui/icon";
import { ResultCard } from "@/components/ResultCard";
import { SamplePicker } from "@/components/SamplePicker";
import {
  analyzeById,
  analyzeRaw,
  fetchSamples,
  type AnalyzeResponse,
  type SeedSample,
} from "@/lib/api";

const PLACEHOLDER = `From: deals@promo.example
Subject: Act now

Verify your prize at http://malware.example/login today.`;

export function Analyzer() {
  const [raw, setRaw] = useState("");
  const [result, setResult] = useState<AnalyzeResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [pendingSampleId, setPendingSampleId] = useState<string | null>(null);
  const [samples, setSamples] = useState<SeedSample[]>([]);
  const resultRef = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();

  // Load picker samples once; absence (empty corpus) is non-fatal — paste still works.
  useEffect(() => {
    let cancelled = false;
    fetchSamples()
      .then((loaded) => {
        if (!cancelled) setSamples(loaded);
      })
      .catch(() => {
        /* picker is optional; ignore load failures */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const run = useCallback(
    async (action: () => Promise<AnalyzeResponse>, sampleId: string | null) => {
      setPending(true);
      setPendingSampleId(sampleId);
      setError(null);
      try {
        const decision = await action();
        setResult(decision);
        // Bring the verdict into view on smaller screens.
        requestAnimationFrame(() =>
          resultRef.current?.scrollIntoView({
            behavior: reduce ? "auto" : "smooth",
            block: "nearest",
          }),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : "Something went wrong analysing that email.");
      } finally {
        setPending(false);
        setPendingSampleId(null);
      }
    },
    [reduce],
  );

  const onSubmit = useCallback(
    (event: React.FormEvent) => {
      event.preventDefault();
      if (!raw.trim() || pending) return;
      void run(() => analyzeRaw(raw), null);
    },
    [raw, pending, run],
  );

  const onPickSample = useCallback(
    (sample: SeedSample) => {
      if (pending) return;
      void run(() => analyzeById(sample.emailId), sample.emailId);
    },
    [pending, run],
  );

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      {/* Input column */}
      <section className="flex flex-col gap-4">
        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <label htmlFor="raw-email" className="text-title-sm text-on-surface-variant">
            Paste a raw email
          </label>
          <Textarea
            id="raw-email"
            data-testid="raw-input"
            value={raw}
            onChange={(e) => setRaw(e.target.value)}
            placeholder={PLACEHOLDER}
            rows={12}
            spellCheck={false}
            aria-label="Raw email"
          />
          <div className="flex items-center gap-3">
            <Button type="submit" data-testid="analyze-button" disabled={pending || !raw.trim()}>
              <Icon
                name={pending && !pendingSampleId ? "progress_activity" : "bolt"}
                className={pending && !pendingSampleId ? "animate-spin text-[20px]" : "text-[20px]"}
              />
              {pending && !pendingSampleId ? "Analysing…" : "Analyze"}
            </Button>
            {raw && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => setRaw("")}
                disabled={pending}
              >
                Clear
              </Button>
            )}
          </div>
        </form>

        <SamplePicker
          samples={samples}
          onPick={onPickSample}
          disabled={pending}
          busyId={pendingSampleId}
        />
      </section>

      {/* Result column */}
      <section ref={resultRef} className="flex flex-col gap-4" aria-live="polite">
        <AnimatePresence mode="wait" initial={false}>
          {error ? (
            <motion.div
              key="error"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: reduce ? 0 : 0.2 }}
              role="alert"
              data-testid="error"
              className="flex items-center gap-3 rounded-lg border border-tier-block/40 bg-tier-block-container px-5 py-4 text-tier-block"
            >
              <Icon name="error" filled />
              <span className="text-body-md text-on-surface">{error}</span>
            </motion.div>
          ) : result ? (
            <ResultCard key="result" result={result} />
          ) : (
            <EmptyState key="empty" />
          )}
        </AnimatePresence>
      </section>
    </div>
  );
}

function EmptyState() {
  return (
    <div
      data-testid="empty-state"
      className="flex h-full min-h-48 flex-col items-center justify-center gap-3 rounded-lg border border-dashed border-outline/60 px-6 py-12 text-center text-on-surface-variant"
    >
      <Icon name="frame_inspect" className="text-[40px] opacity-70" />
      <p className="text-body-md">
        Paste an email or pick a sample — the decision, its tier, reason codes, route, and
        latency appear here.
      </p>
    </div>
  );
}
