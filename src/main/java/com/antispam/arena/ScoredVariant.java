package com.antispam.arena;

/**
 * A minted variant paired with the verdict the fixed defender gave it (story 08.02): whether it
 * <em>bypassed</em> — i.e. the defender would deliver this spam/phish to the inbox. The
 * per-generation results, which the gap-targeting step ({@link AttackLoopService#targetGaps}) filters
 * to the bypassing variants so the next generation attacks what worked.
 *
 * @param variant  the logged variant
 * @param bypassed true when the defender would deliver it (the attack succeeded for this variant)
 */
record ScoredVariant(AdversarialEmail variant, boolean bypassed) {
}
