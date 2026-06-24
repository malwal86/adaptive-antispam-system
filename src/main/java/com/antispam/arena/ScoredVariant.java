package com.antispam.arena;

/**
 * A minted variant paired with whether it <em>beat the fixed defender</em> (story 08.02 / 08.02b) —
 * the attacker's win condition for the variant's {@link Track}: a Track A (abuse) variant beats the
 * defender by being delivered (a bypass), a Track B (legit) variant beats it by being withheld (a
 * false positive). The gap-targeting step ({@link AttackLoopService#targetGaps}) filters to the
 * variants that beat the defender so the next generation re-attacks what worked, whichever track.
 *
 * @param variant      the logged variant
 * @param beatDefender true when the attacker won this variant (bypass for Track A, false positive for
 *                     Track B) — the gap a later generation concentrates on
 */
record ScoredVariant(AdversarialEmail variant, boolean beatDefender) {
}
