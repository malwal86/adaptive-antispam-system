package com.antispam.decision.hardrule;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.TestEmails;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnownBadUrlRuleTest {

    private static KnownBadUrlRule ruleWithDenylist(String... denylist) {
        return new KnownBadUrlRule(new HardRuleProperties(List.of(denylist), List.of()));
    }

    @Test
    void blocks_a_message_linking_to_a_denylisted_host() {
        var match = ruleWithDenylist("malware.example")
                .evaluate(TestEmails.bodyContaining("Click http://malware.example/login now"));

        assertThat(match).contains(new RuleMatch(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL));
    }

    @Test
    void blocks_a_subdomain_of_a_denylisted_host() {
        var match = ruleWithDenylist("malware.example")
                .evaluate(TestEmails.bodyContaining("see https://login.malware.example/x"));

        assertThat(match).isPresent();
    }

    @Test
    void blocks_a_url_carrying_userinfo_and_a_port() {
        var match = ruleWithDenylist("malware.example")
                .evaluate(TestEmails.bodyContaining("https://user@malware.example:8443/x"));

        assertThat(match).isPresent();
    }

    @Test
    void ignores_a_url_not_on_the_denylist() {
        var match = ruleWithDenylist("malware.example")
                .evaluate(TestEmails.bodyContaining("https://good.example/welcome"));

        assertThat(match).isEmpty();
    }

    @Test
    void does_not_match_a_lookalike_that_only_partially_overlaps_the_suffix() {
        // notmalware.example must not be treated as a subdomain of malware.example.
        var match = ruleWithDenylist("malware.example")
                .evaluate(TestEmails.bodyContaining("https://notmalware.example/x"));

        assertThat(match).isEmpty();
    }

    @Test
    void stays_silent_when_the_message_has_no_url() {
        assertThat(ruleWithDenylist("malware.example")
                .evaluate(TestEmails.bodyContaining("plain text, no links here")))
                .isEmpty();
    }

    @Test
    void stays_silent_when_the_denylist_is_empty() {
        assertThat(ruleWithDenylist()
                .evaluate(TestEmails.bodyContaining("http://malware.example/")))
                .isEmpty();
    }
}
