package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.features.FeatureSet.AuthFeatures;
import org.junit.jupiter.api.Test;

/**
 * The bucket router in isolation (story 03.03): an email's SPF/DKIM/DMARC result
 * decides which reputation bucket its signal accrues into. The rule is deliberately
 * strict — only an explicit {@code dmarc=pass} (full alignment) earns the
 * authenticated bucket; everything else (fail, none, unknown, or DMARC absent while
 * only SPF/DKIM pass) is unauthenticated and can never inherit a domain's trust.
 */
class AuthGateTest {

    @Test
    void dmarc_pass_routes_to_the_authenticated_bucket() {
        assertThat(AuthGate.bucketFor(new AuthFeatures("pass", "pass", "pass")))
                .isEqualTo(ReputationBucket.AUTHENTICATED);
    }

    @Test
    void dmarc_pass_is_aligned_even_if_spf_or_dkim_individually_failed() {
        // DMARC passes when at least one aligned mechanism passes; the DMARC verdict is
        // the alignment authority, so a failing SPF alongside dmarc=pass is still aligned.
        assertThat(AuthGate.bucketFor(new AuthFeatures("fail", "pass", "pass")))
                .isEqualTo(ReputationBucket.AUTHENTICATED);
    }

    @Test
    void dmarc_fail_routes_to_the_unauthenticated_bucket() {
        assertThat(AuthGate.bucketFor(new AuthFeatures("pass", "pass", "fail")))
                .isEqualTo(ReputationBucket.UNAUTHENTICATED);
    }

    @Test
    void dmarc_none_or_unknown_is_unauthenticated() {
        // No DMARC assertion is not alignment: a sender whose SPF/DKIM pass but DMARC is
        // absent has not proven the From domain, so it stays out of the trusted bucket.
        assertThat(AuthGate.bucketFor(new AuthFeatures("pass", "pass", "none")))
                .isEqualTo(ReputationBucket.UNAUTHENTICATED);
        assertThat(AuthGate.bucketFor(new AuthFeatures("pass", "pass", "unknown")))
                .isEqualTo(ReputationBucket.UNAUTHENTICATED);
    }

    @Test
    void the_dmarc_token_is_matched_case_insensitively() {
        assertThat(AuthGate.bucketFor(new AuthFeatures("unknown", "unknown", "PASS")))
                .isEqualTo(ReputationBucket.AUTHENTICATED);
    }

    @Test
    void null_auth_is_treated_as_unauthenticated() {
        // Absent auth features (e.g. a manual signal with no auth context) default to
        // the safe side: not trusted.
        assertThat(AuthGate.bucketFor(null)).isEqualTo(ReputationBucket.UNAUTHENTICATED);
    }
}
