package com.antispam.decision.llm;

import com.antispam.reputation.BetaReputation;

/**
 * The sender's reputation rendered as the small, grounded summary the LLM prompt carries
 * (story 05.03) — the part of the grounded context that answers "who is this sender, and
 * how sure are we?". It deliberately exposes only the model-meaningful scalars, not the
 * raw Beta counts: the trust {@link #trustMean mean}, the {@link #evidenceCount} behind it,
 * the {@link #uncertainty} as a standard deviation (largest for a brand-new sender), and
 * whether the mail proved {@link #dmarcAligned DMARC alignment} — the gate that decides
 * which reputation bucket the score was even allowed to draw from (story 03.03).
 *
 * <p>It is a pure value object: {@link #from} is a function of its inputs alone, so the
 * same reputation snapshot always renders the same summary — the reproducibility the
 * grounded context needs to be auditable (AC 4).
 *
 * @param trustMean     the sender's trust estimate in {@code (0,1)} — the Beta mean
 * @param evidenceCount weighted good+bad signals behind the mean, excluding the prior
 * @param uncertainty   the standard deviation of the mean (√variance); shrinks with evidence
 * @param dmarcAligned  whether the mail proved DMARC alignment (so the authenticated
 *                      bucket was used) rather than the neutral-capped bucket
 */
public record SenderReputationSummary(
        double trustMean,
        double evidenceCount,
        double uncertainty,
        boolean dmarcAligned) {

    /**
     * The summary of {@code reputation} for a mail with the given DMARC alignment. The
     * uncertainty is the Beta standard deviation — the same content-independent width the
     * router escalates on — so the model sees the same "how shaky is this prior?" signal
     * the pipeline routed on.
     */
    public static SenderReputationSummary from(BetaReputation reputation, boolean dmarcAligned) {
        return new SenderReputationSummary(
                reputation.mean(),
                reputation.count(),
                Math.sqrt(reputation.variance()),
                dmarcAligned);
    }
}
