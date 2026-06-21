package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The soft auth-gate's blend math in isolation (story 03.03): from a sender's
 * per-bucket evidence, produce the two reputation views an email can be assigned —
 * the full {@code authenticated} view and the neutral-capped {@code unauthenticated}
 * view — and pick between them by the reading email's own auth status.
 *
 * <p>The load-bearing invariant is the <b>neutral cap</b>: the unauthenticated view's
 * mean can sit at or below the prior (neutral) but <em>never</em> above it, so
 * unauthenticated mail can lower a sender's standing yet never inherit trust. The cap
 * is enforced by limiting the bucket's good evidence to {@code (alpha/beta)·bad}, the
 * exact point where the Beta posterior mean equals the prior mean — keeping the view a
 * proper Beta (with honest variance) rather than a clipped scalar.
 */
class GatedReputationTest {

    private static final ReputationProperties PRIORS =
            new ReputationProperties(1.0, 1.0, Duration.ofDays(7));
    private static final double NEUTRAL = 0.5; // prior mean for the symmetric 1/1 prior

    private static GatedReputation gated(double ag, double ab, double ug, double ub) {
        return GatedReputation.from(
                new BucketedReputationCounts(new ReputationCounts(ag, ab), new ReputationCounts(ug, ub)),
                PRIORS);
    }

    @Test
    void authenticated_view_is_the_full_uncapped_beta_of_the_authenticated_bucket() {
        // 8 good / 2 bad authenticated -> mean 0.75, well above neutral: trust is earned.
        GatedReputation reputation = gated(8, 2, 0, 0);

        assertThat(reputation.authenticated().mean()).isCloseTo(0.75, within(1e-12));
        assertThat(reputation.authenticated().good()).isEqualTo(8.0);
        assertThat(reputation.authenticated().bad()).isEqualTo(2.0);
    }

    @Test
    void authenticated_view_ignores_the_unauthenticated_bucket() {
        // Unauthenticated (possibly spoofed) traffic must not move the earned score in
        // either direction: the authenticated view is isolated from it.
        GatedReputation withSpoofTraffic = gated(8, 2, 100, 100);
        GatedReputation clean = gated(8, 2, 0, 0);

        assertThat(withSpoofTraffic.authenticated().mean())
                .isCloseTo(clean.authenticated().mean(), within(1e-12));
    }

    @Test
    void unauthenticated_all_good_is_capped_exactly_at_neutral() {
        // Lots of good unauthenticated mail, no bad: raw mean would be ~1.0, but the cap
        // pins it at neutral -- "never trusted."
        GatedReputation reputation = gated(0, 0, 50, 0);

        assertThat(reputation.unauthenticated().mean()).isCloseTo(NEUTRAL, within(1e-12));
    }

    @Test
    void unauthenticated_bad_pulls_below_neutral_uncapped() {
        // The cap is one-sided: unauthenticated mail can still lower trust below neutral.
        GatedReputation reputation = gated(0, 0, 0, 8);

        assertThat(reputation.unauthenticated().mean()).isLessThan(NEUTRAL);
        // (0+1)/(0+8+1+1) = 1/10
        assertThat(reputation.unauthenticated().mean()).isCloseTo(0.1, within(1e-12));
    }

    @Test
    void unauthenticated_mixed_never_exceeds_neutral() {
        // More good than bad in the unauthenticated bucket still caps at neutral; the
        // good beyond the bad count is discarded for trust purposes.
        GatedReputation reputation = gated(0, 0, 20, 3);

        assertThat(reputation.unauthenticated().mean()).isLessThanOrEqualTo(NEUTRAL);
        assertThat(reputation.unauthenticated().mean()).isCloseTo(NEUTRAL, within(1e-12));
    }

    @Test
    void an_unseen_sender_reads_the_neutral_prior_in_both_views() {
        GatedReputation reputation = gated(0, 0, 0, 0);

        assertThat(reputation.authenticated().mean()).isEqualTo(NEUTRAL);
        assertThat(reputation.unauthenticated().mean()).isEqualTo(NEUTRAL);
    }

    @Test
    void for_auth_status_picks_the_view_matching_the_reading_email() {
        GatedReputation reputation = gated(8, 2, 0, 8);

        // An aligned email is entitled to the earned, full reputation...
        assertThat(reputation.forAuthStatus(true)).isEqualTo(reputation.authenticated());
        // ...an unauthenticated one only ever sees the neutral-capped bucket.
        assertThat(reputation.forAuthStatus(false)).isEqualTo(reputation.unauthenticated());
    }

    @Test
    void cap_holds_under_an_asymmetric_prior() {
        // Neutral is the prior mean, not a hard-coded 0.5: with a 2/1 prior, neutral is
        // 2/3 and an all-good unauthenticated bucket caps there, not at 0.5.
        ReputationProperties skewed = new ReputationProperties(2.0, 1.0, Duration.ofDays(7));
        GatedReputation reputation = GatedReputation.from(
                new BucketedReputationCounts(new ReputationCounts(0, 0), new ReputationCounts(50, 0)),
                skewed);

        assertThat(reputation.unauthenticated().mean()).isCloseTo(2.0 / 3.0, within(1e-12));
    }
}
