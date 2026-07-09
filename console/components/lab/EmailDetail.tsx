"use client";

import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { motion, useReducedMotion } from "framer-motion";
import { Icon } from "@/components/ui/icon";
import { ReasonChip } from "@/components/ReasonChip";
import { EMPHASIZED_EASE } from "@/lib/animation";
import { isPending, type StreamItem } from "@/lib/decisionStream";
import { formatClockTime, shortId } from "@/lib/format";
import { outcomeFor, plainReason, routeLabel, TIERS } from "@/lib/tiers";
import { cn } from "@/lib/utils";

/**
 * The email detail view (opened by clicking a card in the live stream): the whole decision, laid
 * out like an opened message. It shows the envelope (from · subject · a fuller preview), the plain
 * outcome and reason a lay viewer reads on the card, the system's own grounded explanation and
 * machine reason codes, and the decision telemetry — route, latency, sender trust, LLM cost, timing,
 * and ids. Nothing here is recomputed: it renders the same {@link StreamItem} the stream holds, so a
 * quarantine-pending message that resolves while this is open flips in place (its outcome appears,
 * the reason updates) exactly as the underlying card does — never a second dialog, never a retraction.
 *
 * <p>It is a modal overlay (guidelines Part IV): a scrim that blocks input and takes focus, a fade-in
 * under 250ms, Escape / scrim-click / close-button to dismiss. Reduced-motion collapses the fade to
 * an instant, legible state.
 */
export function EmailDetail({ item, onClose }: { item: StreamItem; onClose: () => void }) {
  const reduceMotion = useReducedMotion();
  const closeRef = useRef<HTMLButtonElement>(null);

  const { decision } = item;
  const tier = TIERS[decision.tier];
  const pending = isPending(item);
  const outcome = outcomeFor(decision.tier, decision.delivered);
  const reason = plainReason(decision.reasonCodes, outcome.folder, decision.explanation);
  const sender = decision.sender ?? shortId(decision.emailId);
  const titleId = "email-detail-title";

  // Escape closes; focus lands on the dismiss control so the dialog is keyboard-operable at once.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    closeRef.current?.focus();
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Portal to the body so the overlay stacks above the app chrome (one glass pane, guidelines Part IV).
  // Guard for SSR / the first render before hydration, where document is absent.
  if (typeof document === "undefined") return null;

  return createPortal(
    <div className="fixed inset-0 z-50 grid place-items-center p-4">
      {/* Scrim: blocks input, dismisses on click, and gently de-emphasises the console behind. */}
      <motion.div
        data-testid="detail-scrim"
        onClick={onClose}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: reduceMotion ? 0 : 0.2, ease: "easeOut" }}
        className="absolute inset-0 bg-black/40 backdrop-blur-sm"
      />

      <motion.div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-testid="email-detail"
        data-tier={decision.tier}
        data-folder={outcome.folder}
        initial={{ opacity: 0, y: reduceMotion ? 0 : 12, scale: reduceMotion ? 1 : 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: reduceMotion ? 0 : 12, scale: reduceMotion ? 1 : 0.98 }}
        transition={{ duration: reduceMotion ? 0 : 0.24, ease: EMPHASIZED_EASE }}
        className={cn(
          "relative flex w-full max-w-lg flex-col gap-5 rounded-lg border bg-surface-container p-6 shadow-lg shadow-black/10 ring-1",
          pending ? "border-tier-quarantine/50 ring-tier-quarantine/40" : cn(tier.accentBorder, tier.ring),
        )}
      >
        {/* Header: the outcome badge + tier, and the dismiss control. */}
        <div className="flex items-start justify-between gap-4">
          <div className={cn("flex items-center gap-3", pending ? "text-tier-quarantine" : tier.accentText)}>
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-md bg-surface/50">
              <Icon name={pending ? "hourglass_top" : outcome.icon} filled className="text-[26px]" />
            </span>
            <div className="min-w-0">
              <h2 id={titleId} className="text-title-lg font-medium text-on-surface" data-testid="detail-title">
                {pending ? "Checking this message…" : tier.label}
              </h2>
              {pending ? (
                <p data-testid="detail-pending" className="text-body-md text-on-surface-variant">
                  Held for a closer look. No verdict yet.
                </p>
              ) : (
                <p
                  data-testid="detail-outcome"
                  className="inline-flex items-center gap-1 text-body-md text-on-surface-variant"
                >
                  <Icon name={outcome.folder === "inbox" ? "inbox" : "report"} className="text-[16px] leading-none" />
                  {outcome.verb}
                </p>
              )}
            </div>
          </div>

          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label="Close email details"
            data-testid="detail-close"
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-variant hover:text-on-surface"
          >
            <Icon name="close" className="text-[20px]" />
          </button>
        </div>

        {/* The envelope, read like an opened message. */}
        <div className="flex flex-col gap-1 rounded-md border border-outline/40 bg-surface/40 p-4">
          <span className="truncate text-title-sm font-medium text-on-surface" data-testid="detail-sender">
            {sender}
          </span>
          {decision.subject && (
            <span className="text-body-lg text-on-surface" data-testid="detail-subject">
              {decision.subject}
            </span>
          )}
          {decision.preview && (
            <p className="mt-1 text-body-md text-on-surface-variant" data-testid="detail-preview">
              {decision.preview}
            </p>
          )}
        </div>

        {/* Plain reason — the one-liner a lay viewer reads. */}
        <p className="text-body-md text-on-surface" data-testid="detail-reason">
          {pending ? "Taking a closer look at this one…" : reason}
        </p>

        {/* The system's own grounded sentence, when it differs from the plain reason. */}
        {decision.explanation && decision.explanation.trim().length > 0 && (
          <div className="flex flex-col gap-1 border-t border-outline/40 pt-4">
            <span className="text-label-md text-on-surface-variant">What the system found</span>
            <p className="text-body-md text-on-surface" data-testid="detail-explanation">
              {decision.explanation}
            </p>
          </div>
        )}

        {/* Machine reason codes, if any fired. */}
        {decision.reasonCodes.length > 0 && (
          <div className={cn("flex flex-wrap gap-2", tier.accentText)}>
            {decision.reasonCodes.map((code) => (
              <ReasonChip key={code} code={code} />
            ))}
          </div>
        )}

        {/* Decision telemetry. */}
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 border-t border-outline/40 pt-4 text-on-surface-variant sm:grid-cols-3">
          <Fact icon="route" label="Route" value={routeLabel(decision.routeUsed)} testid="detail-route" />
          <Fact icon="timer" label="Decided in" value={`${decision.latencyMs} ms`} testid="detail-latency" />
          <Fact icon="schedule" label="At" value={formatClockTime(decision.decidedAt)} testid="detail-time" />
          {typeof decision.posterior === "number" && (
            <Fact
              icon="verified_user"
              label="Sender trust"
              value={`${Math.round((1 - decision.posterior) * 100)}%`}
              testid="detail-trust"
            />
          )}
          {typeof decision.llmCostUsd === "number" && (
            <Fact
              icon="payments"
              label="LLM cost"
              value={`$${decision.llmCostUsd.toFixed(4)}`}
              testid="detail-cost"
            />
          )}
          <Fact icon="fingerprint" label="Email id" value={shortId(decision.emailId)} testid="detail-id" />
        </dl>
      </motion.div>
    </div>,
    document.body,
  );
}

/** One labelled telemetry fact in the detail footer grid. */
function Fact({
  icon,
  label,
  value,
  testid,
}: {
  icon: string;
  label: string;
  value: string;
  testid: string;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="inline-flex items-center gap-1 text-label-md">
        <Icon name={icon} className="text-[15px] leading-none" />
        {label}
      </dt>
      <dd className="text-body-md text-on-surface" data-testid={testid}>
        {value}
      </dd>
    </div>
  );
}
