package com.antispam.decision.hardrule;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.TestEmails;
import com.antispam.decision.hardrule.HardRuleProperties.Brand;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrandSpoofAuthRuleTest {

    private static BrandSpoofAuthRule rule() {
        return new BrandSpoofAuthRule(
                new HardRuleProperties(List.of(), List.of(new Brand("paypal", "paypal.com"))));
    }

    @Test
    void quarantines_a_brand_claiming_domain_with_failing_dmarc() {
        var match = rule().evaluate(TestEmails.from("paypal.account-verify.com", "spf=pass; dmarc=fail"));

        assertThat(match).contains(new RuleMatch(Decision.QUARANTINE, ReasonCode.MALFORMED_AUTH_BRAND_SPOOF));
    }

    @Test
    void quarantines_a_brand_claiming_domain_with_no_auth_results_at_all() {
        assertThat(rule().evaluate(TestEmails.from("paypal.account-verify.com", null))).isPresent();
    }

    @Test
    void allows_the_real_brand_domain_when_dmarc_passes() {
        assertThat(rule().evaluate(TestEmails.from("paypal.com", "spf=pass; dmarc=pass"))).isEmpty();
    }

    @Test
    void allows_a_brand_subdomain_when_dmarc_passes() {
        assertThat(rule().evaluate(TestEmails.from("mail.paypal.com", "dmarc=pass"))).isEmpty();
    }

    @Test
    void quarantines_even_the_real_brand_domain_when_dmarc_does_not_pass() {
        // A brand-claiming domain that is the real domain but unauthenticated is
        // still suspicious — the claim is only legitimate when auth backs it.
        assertThat(rule().evaluate(TestEmails.from("paypal.com", "dmarc=fail"))).isPresent();
    }

    @Test
    void ignores_mail_that_does_not_claim_any_brand() {
        assertThat(rule().evaluate(TestEmails.from("newsletter.example.com", "dmarc=fail"))).isEmpty();
    }

    @Test
    void ignores_mail_with_no_parsed_sender_domain() {
        assertThat(rule().evaluate(TestEmails.from(null, "dmarc=fail"))).isEmpty();
    }
}
