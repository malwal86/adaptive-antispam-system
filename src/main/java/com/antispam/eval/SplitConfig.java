package com.antispam.eval;

/**
 * The two knobs that pin a split down to a single reproducible outcome: how much
 * of the corpus to hold out, and the seed that breaks ties deterministically.
 *
 * <p>Reproducibility (story 11.01) is a hard requirement — the same corpus and the
 * same {@code (evalFraction, seed)} must always produce the byte-identical split,
 * so calibration and the promotion gate can be re-derived and audited. The time
 * ordering does the real assigning; the seed only orders families whose timelines
 * are indistinguishable (identical representative timestamps), and is carried
 * explicitly so that choice is recorded rather than implicit.
 *
 * @param evalFraction the target share of emails to hold out for eval, in the open
 *                     interval {@code (0, 1)} — the realized eval share differs
 *                     slightly because whole families move together and are never split
 * @param seed         deterministic tie-breaker among families with identical
 *                     representative timestamps
 */
public record SplitConfig(double evalFraction, long seed) {

    public SplitConfig {
        if (!(evalFraction > 0.0 && evalFraction < 1.0)) {
            throw new IllegalArgumentException(
                    "evalFraction must be in the open interval (0, 1), was: " + evalFraction);
        }
    }
}
