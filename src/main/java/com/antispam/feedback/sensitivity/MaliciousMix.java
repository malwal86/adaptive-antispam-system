package com.antispam.feedback.sensitivity;

/**
 * How a population of a given size splits into good-faith and malicious personas at one point of the
 * sensitivity sweep (story 07.04). Pure and unit-testable so the mix policy is auditable without a
 * database. The malicious half is split evenly between the two attack vectors — report bombers
 * (mass-report delivered ham) and rescue bombers (mass-rescue withheld spam) — so the sweep stresses
 * both directions of the feedback channel at once. An odd malicious count gives the extra to the
 * report bombers (an arbitrary but fixed tie-break, so the mix stays reproducible).
 *
 * @param reportBombers distinct report-bomber identities ({@code >= 0})
 * @param rescueBombers distinct rescue-bomber identities ({@code >= 0})
 * @param genuine       distinct good-faith identities ({@code >= 0})
 */
public record MaliciousMix(int reportBombers, int rescueBombers, int genuine) {

    public MaliciousMix {
        if (reportBombers < 0 || rescueBombers < 0 || genuine < 0) {
            throw new IllegalArgumentException(
                    "mix counts must be >= 0: report=" + reportBombers + " rescue=" + rescueBombers
                            + " genuine=" + genuine);
        }
    }

    /**
     * The mix for a malicious fraction of a population.
     *
     * @param populationSize total distinct personas ({@code >= 1})
     * @param fraction       fraction of the population that is malicious, in {@code [0,1]}
     */
    public static MaliciousMix forFraction(int populationSize, double fraction) {
        if (populationSize < 1) {
            throw new IllegalArgumentException("populationSize must be >= 1 but was " + populationSize);
        }
        if (fraction < 0 || fraction > 1 || Double.isNaN(fraction)) {
            throw new IllegalArgumentException("fraction must be in [0,1] but was " + fraction);
        }
        int malicious = (int) Math.round(fraction * populationSize);
        int reportBombers = (malicious + 1) / 2; // ceil — the odd one bombs reports
        int rescueBombers = malicious / 2;        // floor
        int genuine = populationSize - malicious;
        return new MaliciousMix(reportBombers, rescueBombers, genuine);
    }

    /** Total malicious identities across both attack vectors. */
    public int bomberCount() {
        return reportBombers + rescueBombers;
    }

    /** Total population size this mix represents. */
    public int populationSize() {
        return bomberCount() + genuine;
    }
}
