package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.reputation.BetaReputation;
import org.junit.jupiter.api.Test;

/**
 * The grounded reputation summary (story 05.03): the model-meaningful scalars derived from a
 * sender's Beta reputation — trust mean, evidence count, and the uncertainty as a standard
 * deviation — plus the auth-alignment flag that decided which bucket was read.
 */
class SenderReputationSummaryTest {

    @Test
    void summarizes_mean_evidence_and_uncertainty_from_the_beta() {
        // Beta(good=8, bad=2, alpha=1, beta=1): mean = 9/12 = 0.75, evidence = good+bad = 10.
        BetaReputation rep = new BetaReputation(8.0, 2.0, 1.0, 1.0);

        SenderReputationSummary summary = SenderReputationSummary.from(rep, true);

        assertThat(summary.trustMean()).isEqualTo(rep.mean());
        assertThat(summary.trustMean()).isCloseTo(0.75, within(1e-9));
        assertThat(summary.evidenceCount()).isEqualTo(10.0);
        assertThat(summary.uncertainty()).isEqualTo(Math.sqrt(rep.variance()));
        assertThat(summary.dmarcAligned()).isTrue();
    }

    @Test
    void a_new_sender_carries_the_widest_uncertainty_and_no_evidence() {
        // The prior alone — no observed signal: highest uncertainty, zero evidence.
        SenderReputationSummary newSender = SenderReputationSummary.from(new BetaReputation(0, 0, 1, 1), false);
        SenderReputationSummary seasoned = SenderReputationSummary.from(new BetaReputation(50, 10, 1, 1), false);

        assertThat(newSender.evidenceCount()).isZero();
        assertThat(newSender.uncertainty()).isGreaterThan(seasoned.uncertainty());
        assertThat(newSender.dmarcAligned()).isFalse();
    }
}
