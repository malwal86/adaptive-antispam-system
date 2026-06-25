// Small presentation helpers shared across the analyzer and lab console. These
// are pure display conventions (never decision logic): the console renders what
// the API decided, and these only shape how a value reads on screen.

/**
 * Formats an ISO timestamp as a local wall-clock time, falling back to the raw
 * string when it is not a parseable date. Used by every decision card's footer
 * so a verdict's time reads consistently.
 */
export function formatClockTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString();
}

/**
 * The 8-character prefix used to show an opaque id (email/run) compactly. Long
 * enough to disambiguate at a glance, short enough not to dominate a row.
 */
export function shortId(id: string): string {
  return id.slice(0, 8);
}
