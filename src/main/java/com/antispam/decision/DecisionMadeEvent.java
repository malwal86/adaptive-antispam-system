package com.antispam.decision;

/**
 * Published after a decision is persisted, so live consumers (the Abuse Lab Console's decision
 * stream, Epic 12) can observe decisions as they happen without the pipeline depending on them.
 *
 * <p>This is a Spring {@code ApplicationEvent} payload, not a message-spine event: it is in-process
 * and best-effort, purely to drive the live UI. The authoritative record is the persisted
 * {@link Classification} the event carries — the same row {@code GET /analyze/{id}} would return.
 *
 * @param classification the decision that was just recorded
 */
public record DecisionMadeEvent(Classification classification) {}
