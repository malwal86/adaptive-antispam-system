package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * The Beta-reputation math in isolation (story 03.01): mean and variance from
 * weighted good/bad counts plus the {@code (alpha, beta)} prior. This is the deep,
 * pure unit the rest of the epic builds on, so it is tested with no Spring and no
 * database — only the formula and its boundaries.
 *
 * <p>"Uncertainty for free" is the property that matters downstream: a brand-new
 * sender must come back with <em>wide</em> variance (which later routes it to the
 * LLM), and that variance must shrink monotonically as evidence accrues.
 */
class BetaReputationTest {

    private static final double ALPHA = 1.0;
    private static final double BETA = 1.0;

    @Test
    void a_new_sender_has_the_prior_mean_and_maximum_variance() {
        // Beta(1,1) is uniform on [0,1]: mean 0.5 and the largest variance any Beta
        // can have, 1/12 -- the "wide, uncertain" new-sender prior.
        BetaReputation prior = new BetaReputation(0, 0, ALPHA, BETA);

        assertThat(prior.mean()).isEqualTo(0.5);
        assertThat(prior.variance()).isCloseTo(1.0 / 12.0, within(1e-12));
        assertThat(prior.count()).isZero();
    }

    @Test
    void mean_follows_the_beta_formula() {
        // (good+alpha)/(good+bad+alpha+beta) = (8+1)/(8+2+1+1) = 9/12 = 0.75
        BetaReputation reputation = new BetaReputation(8, 2, ALPHA, BETA);

        assertThat(reputation.mean()).isCloseTo(0.75, within(1e-12));
        assertThat(reputation.count()).isEqualTo(10.0);
    }

    @Test
    void variance_follows_the_beta_formula() {
        // a=9, b=3: a*b / ((a+b)^2 * (a+b+1)) = 27 / (144*13) = 27/1872
        BetaReputation reputation = new BetaReputation(8, 2, ALPHA, BETA);

        assertThat(reputation.variance()).isCloseTo(27.0 / 1872.0, within(1e-12));
    }

    @Test
    void variance_shrinks_monotonically_as_evidence_grows() {
        // Same 80% good ratio, increasing sample size: the estimate gets more certain.
        double v1 = new BetaReputation(8, 2, ALPHA, BETA).variance();
        double v2 = new BetaReputation(80, 20, ALPHA, BETA).variance();
        double v3 = new BetaReputation(800, 200, ALPHA, BETA).variance();

        assertThat(v2).isLessThan(v1);
        assertThat(v3).isLessThan(v2);
    }

    @Test
    void more_good_signals_raise_the_mean_more_bad_lower_it() {
        double neutral = new BetaReputation(5, 5, ALPHA, BETA).mean();

        assertThat(new BetaReputation(9, 1, ALPHA, BETA).mean()).isGreaterThan(neutral);
        assertThat(new BetaReputation(1, 9, ALPHA, BETA).mean()).isLessThan(neutral);
    }

    @Test
    void weighted_counts_are_supported() {
        // Counts are weighted sums (story 03.03 accrues fractional weight), so the math
        // must accept non-integer good/bad, not just whole observations.
        BetaReputation reputation = new BetaReputation(2.5, 0.5, ALPHA, BETA);

        assertThat(reputation.count()).isEqualTo(3.0);
        assertThat(reputation.mean()).isCloseTo(3.5 / 5.0, within(1e-12));
    }

    @Test
    void rejects_a_non_positive_prior() {
        // A proper Beta needs alpha, beta > 0; zero/negative pseudo-counts are a
        // misconfiguration, caught loudly rather than producing a NaN score.
        assertThatThrownBy(() -> new BetaReputation(1, 1, 0, BETA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BetaReputation(1, 1, ALPHA, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_negative_counts() {
        assertThatThrownBy(() -> new BetaReputation(-1, 0, ALPHA, BETA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BetaReputation(0, -1, ALPHA, BETA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
