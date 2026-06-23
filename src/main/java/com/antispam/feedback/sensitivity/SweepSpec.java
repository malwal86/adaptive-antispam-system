package com.antispam.feedback.sensitivity;

import java.util.List;

/**
 * A reproducible request for a malicious-fraction sensitivity sweep (story 07.04): run the feedback
 * loop over a population at each malicious fraction and measure how far the bombers move state
 * through the 07.03 gate. Everything is seeded so the same spec yields the same curve (AC 4).
 *
 * @param baseSeed          base seed; each fraction runs at {@code baseSeed + index}, so points are
 *                          independent yet reproducible
 * @param populationSize    distinct personas per point ({@code >= 1})
 * @param maliciousFractions the fractions to sweep (e.g. {@code [0.0, 0.1, 0.2, 0.3, 0.4]}); non-empty,
 *                          each in {@code [0,1]}
 * @param streamPerSender   decided emails per scenario sender (ham and spam) the population acts on
 *                          ({@code >= 1}); larger means more bomber draws and a denser attack
 */
public record SweepSpec(
        long baseSeed,
        int populationSize,
        List<Double> maliciousFractions,
        int streamPerSender) {

    public SweepSpec {
        if (populationSize < 1) {
            throw new IllegalArgumentException("populationSize must be >= 1 but was " + populationSize);
        }
        if (maliciousFractions == null || maliciousFractions.isEmpty()) {
            throw new IllegalArgumentException("maliciousFractions must name at least one fraction");
        }
        maliciousFractions = List.copyOf(maliciousFractions);
        for (Double fraction : maliciousFractions) {
            if (fraction == null || fraction < 0 || fraction > 1 || Double.isNaN(fraction)) {
                throw new IllegalArgumentException("each malicious fraction must be in [0,1] but was " + fraction);
            }
        }
        if (streamPerSender < 1) {
            throw new IllegalArgumentException("streamPerSender must be >= 1 but was " + streamPerSender);
        }
    }
}
