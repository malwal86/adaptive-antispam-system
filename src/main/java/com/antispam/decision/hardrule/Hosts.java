package com.antispam.decision.hardrule;

/**
 * Host-matching shared by the hard rules. The subdomain check has a subtle but
 * security-relevant shape — the {@code "." +} prefix is what stops
 * {@code evil-paypal.com} from matching {@code paypal.com} while still letting
 * {@code login.paypal.com} match — so it lives in one named place rather than being
 * re-derived per rule.
 */
final class Hosts {

    private Hosts() {
    }

    /**
     * Whether {@code host} is {@code domain} itself or a subdomain of it. Both
     * arguments must already be lower-cased by the caller.
     */
    static boolean isHostOrSubdomainOf(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
