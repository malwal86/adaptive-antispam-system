package com.antispam.feedback.gate;

import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationSignal;

/**
 * The unit feedback corroborates within (story 07.03): a sender, a polarity, and an accrual bucket.
 * The sender is the campaign proxy — corroboration is "multiple independent users agreeing about
 * this sender", which is exactly what a campaign-level report is (PRD §Subsystem 7). Polarity and
 * bucket are part of the key because they are intrinsic to the resulting reputation event: GOOD and
 * BAD are distinct Beta observations, and an authenticated signal must never be folded together
 * with an unauthenticated one (story 03.03). One trusted key yields one reputation event.
 *
 * @param senderKey the sender identity (com.antispam.event.SenderKey)
 * @param signal    the asserted polarity
 * @param bucket    the accrual bucket the mail's auth earns
 */
public record CorroborationKey(String senderKey, ReputationSignal signal, ReputationBucket bucket) {

    public CorroborationKey {
        if (senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException("senderKey must not be blank");
        }
        if (signal == null) {
            throw new IllegalArgumentException("signal is required");
        }
        if (bucket == null) {
            throw new IllegalArgumentException("bucket is required");
        }
    }
}
