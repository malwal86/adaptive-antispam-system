package com.antispam.decision.llm;

/**
 * Which budget window denied an LLM call (story 05.04). The two caps are checked in order — the
 * daily sub-cap first, then the rolling monthly cap — so a denial attributes the cause: a
 * {@link #DAILY} denial means "a bad/adversarial day was stopped while the month still had room"
 * (PRD §Subsystem 5: "a bad/adversarial day can't burn the month"); a {@link #MONTHLY} denial means
 * the month itself is spent. Carried on the denied {@link BudgetReservation} and surfaced as the
 * {@code scope} tag on the budget-denied metric, so the stopped fraction can be read by cause.
 */
public enum BudgetScope {

    /** The daily sub-cap was reached; the monthly cap may still have room. */
    DAILY("daily"),

    /** The rolling monthly cap was reached; no further calls until the month rolls over. */
    MONTHLY("monthly");

    private final String tag;

    BudgetScope(String tag) {
        this.tag = tag;
    }

    /** The lowercase metric tag value for this scope. */
    public String tag() {
        return tag;
    }
}
