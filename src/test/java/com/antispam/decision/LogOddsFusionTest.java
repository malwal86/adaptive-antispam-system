package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * The crux of Subsystem 2 (story 04.04): content evidence and sender reputation are
 * combined in log-odds with the model's training base rate subtracted once, so the
 * prior is counted exactly once. These cases pin the formula against hand-computed
 * numbers, the {@code prior == π_train} identity (the double-count is provably gone),
 * monotonicity, the variance→band mapping, and numerical safety at the 0/1 edges.
 */
class LogOddsFusionTest {

    private static final double TOL = 1e-4;

    @Test
    void matches_a_hand_computed_posterior() {
        // logit(0.9)+logit(0.7)-logit(0.5) = 2.1972246 + 0.8472979 - 0 = 3.0445225
        // sigmoid(3.0445225) = 0.9545314
        FusedScore fused = LogOddsFusion.fuse(0.9, 0.0, 0.7, 0.5);

        assertThat(fused.posteriorLogit()).isCloseTo(3.0445225, within(TOL));
        assertThat(fused.posterior()).isCloseTo(0.9545314, within(TOL));
    }

    @Test
    void leaves_the_model_score_untouched_when_the_sender_sits_at_the_base_rate() {
        // reputation_prior == π_train: the two logit(π_train) terms cancel, so the
        // posterior is exactly the calibrated model score — the double-count is removed.
        double piTrain = 0.6666666666666666;

        FusedScore fused = LogOddsFusion.fuse(piTrain, 0.0, 0.8, piTrain);

        assertThat(fused.posterior()).isCloseTo(0.8, within(TOL));
    }

    @Test
    void subtracting_the_training_prior_shifts_the_result() {
        // A neutral sender (prior 0.5) is *less* abusive than the training base rate
        // (0.6667), so the correct posterior is pulled below the model's raw 0.8.
        // Without the −logit(π_train) term the posterior would be exactly 0.8.
        FusedScore corrected = LogOddsFusion.fuse(0.5, 0.0, 0.8, 0.6666666666666666);
        double naiveWithoutSubtraction = LogOddsFusion.sigmoid(
                LogOddsFusion.logit(0.5) + LogOddsFusion.logit(0.8));

        assertThat(naiveWithoutSubtraction).isCloseTo(0.8, within(TOL));
        assertThat(corrected.posterior()).isCloseTo(0.6666667, within(TOL));
        assertThat(corrected.posterior()).isNotCloseTo(naiveWithoutSubtraction, within(0.05));
    }

    @Test
    void posterior_rises_monotonically_with_the_spam_prior() {
        double lowPrior = LogOddsFusion.fuse(0.3, 0.0, 0.6, 0.5).posterior();
        double highPrior = LogOddsFusion.fuse(0.7, 0.0, 0.6, 0.5).posterior();

        assertThat(highPrior).isGreaterThan(lowPrior);
    }

    @Test
    void widens_the_uncertainty_band_as_beta_variance_grows() {
        // Everything but the reputation variance held fixed: a more uncertain sender
        // (wider Beta) yields a wider routing band — the signal Epic 05 escalates on.
        double tightBand = LogOddsFusion.fuse(0.5, 0.001, 0.8, 0.6).uncertaintyBand();
        double wideBand = LogOddsFusion.fuse(0.5, 0.02, 0.8, 0.6).uncertaintyBand();

        assertThat(wideBand).isGreaterThan(tightBand);
        assertThat(tightBand).isGreaterThan(0.0);
    }

    @Test
    void emits_a_zero_band_when_the_sender_carries_no_uncertainty() {
        assertThat(LogOddsFusion.fuse(0.5, 0.0, 0.8, 0.6).uncertaintyBand()).isZero();
    }

    @Test
    void reports_sender_uncertainty_as_the_reputation_standard_deviation() {
        // senderUncertainty is √variance — the routing signal for unseen senders (story 05.01).
        assertThat(LogOddsFusion.fuse(0.5, 0.04, 0.8, 0.6).senderUncertainty())
                .isCloseTo(0.2, within(1e-12));
        assertThat(LogOddsFusion.fuse(0.5, 0.0, 0.8, 0.6).senderUncertainty()).isZero();
    }

    @Test
    void sender_uncertainty_is_content_independent_unlike_the_attenuated_band() {
        // The bug this guards: a confident verdict drives the posterior to the saturated tail,
        // where the attenuated band collapses toward zero — but the sender is just as unknown, so
        // senderUncertainty must NOT shrink with content confidence (it is the same √variance for a
        // confident and an ambiguous model score). Otherwise a new sender's confident mail would
        // wrongly skip the LLM.
        double variance = 0.0833;
        FusedScore confident = LogOddsFusion.fuse(0.5, variance, 0.999, 0.5);
        FusedScore ambiguous = LogOddsFusion.fuse(0.5, variance, 0.5, 0.5);

        assertThat(confident.senderUncertainty()).isEqualTo(ambiguous.senderUncertainty());
        assertThat(confident.senderUncertainty()).isCloseTo(Math.sqrt(variance), within(1e-12));
        // ...whereas the attenuated band does collapse as the verdict becomes confident.
        assertThat(confident.uncertaintyBand()).isLessThan(ambiguous.uncertaintyBand());
    }

    @Test
    void stays_finite_and_in_range_at_the_probability_edges() {
        FusedScore certainSpam = LogOddsFusion.fuse(1.0, 0.0, 1.0, 0.5);
        FusedScore certainHam = LogOddsFusion.fuse(0.0, 0.0, 0.0, 0.5);

        assertThat(certainSpam.posterior()).isBetween(0.0, 1.0).isGreaterThan(0.99);
        assertThat(certainHam.posterior()).isBetween(0.0, 1.0).isLessThan(0.01);
        assertThat(certainSpam.posteriorLogit()).isFinite();
        assertThat(certainHam.posteriorLogit()).isFinite();
    }

    @Test
    void rejects_inputs_outside_their_domains() {
        assertThatThrownBy(() -> LogOddsFusion.fuse(1.5, 0.0, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogOddsFusion.fuse(0.5, -0.1, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogOddsFusion.fuse(0.5, 0.0, Double.NaN, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogOddsFusion.fuse(0.5, 0.0, 0.5, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
