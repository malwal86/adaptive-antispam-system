package com.antispam.feedback.gate;

import com.antispam.decision.Probabilities;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationSignal;
import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * One raw feedback event after the gate has resolved and weighted it (story 07.03): the polarity it
 * asserts, the sender and accrual bucket it would move, the persona's trust, and the resulting
 * per-item weight. This is the auditable per-item unit AC 5 calls for — everything needed to
 * corroborate a group and, if trusted, write both sinks with full provenance, with no further
 * re-join to {@code feedback_events} / {@code emails}.
 *
 * <p>It deliberately carries primitives rather than the source {@link
 * com.antispam.feedback.FeedbackEvent} so the corroboration logic is pure and trivially unit-
 * testable. {@link com.antispam.feedback.FeedbackAction#IGNORE} events never become a
 * {@code WeightedFeedback}: they assert no {@link ReputationSignal} ({@link FeedbackSignal}) and are
 * dropped before weighting.
 *
 * @param emailId     the decided email this feedback is about (the retrain-label target)
 * @param personaId   the producing persona — the unit of independence for corroboration
 * @param senderKey   the email's sender identity (the reputation target / campaign proxy); non-blank
 * @param signal      the polarity asserted ({@link FeedbackSignal})
 * @param bucket      the accrual bucket the email's auth earns ({@link com.antispam.reputation.AuthGate})
 * @param groundTruth the email's true class — the label written to the retrain sink
 * @param confidence  the sampler's confidence in the action, in {@code [0,1]} (recorded for audit)
 * @param trust       the persona's trust, in {@code [0,1]} ({@link FeedbackWeighting})
 * @param weight      the per-item contribution {@code trust × confidence}, in {@code [0,1]}
 */
public record WeightedFeedback(
        UUID emailId,
        UUID personaId,
        String senderKey,
        ReputationSignal signal,
        ReputationBucket bucket,
        GroundTruthLabel groundTruth,
        double confidence,
        double trust,
        double weight) {

    public WeightedFeedback {
        if (emailId == null) {
            throw new IllegalArgumentException("emailId is required");
        }
        if (personaId == null) {
            throw new IllegalArgumentException("personaId is required");
        }
        if (senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException("senderKey must not be blank");
        }
        if (signal == null) {
            throw new IllegalArgumentException("signal is required");
        }
        if (bucket == null) {
            throw new IllegalArgumentException("bucket is required");
        }
        if (groundTruth == null) {
            throw new IllegalArgumentException("groundTruth is required");
        }
        Probabilities.requireUnit("confidence", confidence);
        Probabilities.requireUnit("trust", trust);
        Probabilities.requireUnit("weight", weight);
    }

    /** The group this item corroborates within: same sender, polarity, and accrual bucket. */
    public CorroborationKey key() {
        return new CorroborationKey(senderKey, signal, bucket);
    }
}
