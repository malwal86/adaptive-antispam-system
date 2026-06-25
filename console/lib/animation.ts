// Shared motion tokens for the console. Centralising the easing curve keeps every
// entrance and transition on the same Material 3 footing (see the animation
// guidelines) rather than re-declaring the cubic-bézier in each component.

/**
 * Material 3 emphasized-decelerate easing — energetic at the start, settling
 * gently at rest. The default curve for short (≤300ms) entrances and value
 * transitions across the cards, route mix bar, and cost meter.
 */
export const EMPHASIZED_EASE = [0.05, 0.7, 0.1, 1] as const;
