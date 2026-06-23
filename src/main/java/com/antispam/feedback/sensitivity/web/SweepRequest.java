package com.antispam.feedback.sensitivity.web;

import com.antispam.feedback.sensitivity.SweepSpec;
import java.util.List;

/**
 * The {@code POST /feedback/sensitivity} request body (story 07.04). Every field is optional so the
 * demo can fire the sweep with an empty body and get a sensible default curve; supplied fields
 * override. Maps onto a {@link SweepSpec}.
 *
 * @param seed             base seed; defaults to a fixed value so the default sweep is reproducible
 * @param populationSize   distinct personas per point; defaults to 20
 * @param maliciousFractions fractions to sweep; defaults to 0%→40% in 10-point steps
 * @param streamPerSender  decided emails per scenario sender; defaults to 20
 */
public record SweepRequest(
        Long seed,
        Integer populationSize,
        List<Double> maliciousFractions,
        Integer streamPerSender) {

    private static final long DEFAULT_SEED = 1L;
    private static final int DEFAULT_POPULATION_SIZE = 20;
    private static final List<Double> DEFAULT_FRACTIONS = List.of(0.0, 0.1, 0.2, 0.3, 0.4);
    private static final int DEFAULT_STREAM_PER_SENDER = 20;

    public SweepSpec toSpec() {
        return new SweepSpec(
                seed != null ? seed : DEFAULT_SEED,
                populationSize != null ? populationSize : DEFAULT_POPULATION_SIZE,
                maliciousFractions != null && !maliciousFractions.isEmpty() ? maliciousFractions : DEFAULT_FRACTIONS,
                streamPerSender != null ? streamPerSender : DEFAULT_STREAM_PER_SENDER);
    }
}
