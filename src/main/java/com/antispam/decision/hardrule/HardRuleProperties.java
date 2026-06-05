package com.antispam.decision.hardrule;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Data driving the hard rules, bound from configuration under
 * {@code antispam.hard-rules}. Keeping the denylists and brand list in metadata
 * (not in rule logic) means the policy can change without a code change — and
 * later stories can swap these demo defaults for real downloaded feeds (01.03)
 * without touching the engine.
 *
 * @param urlDenylist     hosts/domains that, if linked anywhere in a message,
 *                        make it an immediate block (a host matches an entry when
 *                        it equals the entry or is a subdomain of it)
 * @param highValueBrands brands worth impersonating; mail that claims one of
 *                        these without aligned authentication is quarantined
 */
@ConfigurationProperties(prefix = "antispam.hard-rules")
public record HardRuleProperties(
        List<String> urlDenylist,
        List<Brand> highValueBrands) {

    public HardRuleProperties {
        // Absent config sections bind to null; normalize to empty so rules can
        // iterate without null checks and so an unconfigured rule simply never fires.
        urlDenylist = urlDenylist == null ? List.of() : List.copyOf(urlDenylist);
        highValueBrands = highValueBrands == null ? List.of() : List.copyOf(highValueBrands);
    }

    /**
     * A high-value brand and the domain it legitimately sends from.
     *
     * @param name   the brand token expected to appear in spoofing sender domains
     *               (e.g. {@code paypal})
     * @param domain the brand's authentic sending domain (e.g. {@code paypal.com})
     */
    public record Brand(String name, String domain) {
    }
}
