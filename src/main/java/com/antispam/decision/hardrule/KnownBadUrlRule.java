package com.antispam.decision.hardrule;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.ingest.Email;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Blocks any message that links to a denylisted host. A known-bad URL is the
 * cheapest, most decisive signal there is: if the mail points at a host we have
 * already condemned, no model is needed.
 *
 * <p>Hosts are scanned out of the raw message bytes (the byte-faithful canonical
 * content), so links in the body — not just headers — are caught. A host matches
 * the denylist when it equals an entry or is a subdomain of it, so denylisting
 * {@code malware.example} also blocks {@code login.malware.example}.
 */
@Component
@Order(10)
public class KnownBadUrlRule implements HardRule {

    // Captures the host portion of an http(s) URL: everything up to the first
    // path separator, whitespace, or closing delimiter. Good enough for catching
    // links in real-world mail; full URL parsing is unnecessary for a host check.
    private static final Pattern URL = Pattern.compile(
            "https?://([^/\\s\"'>)\\]]+)", Pattern.CASE_INSENSITIVE);

    private final HardRuleProperties properties;

    @Autowired
    public KnownBadUrlRule(HardRuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<RuleMatch> evaluate(Email email) {
        List<String> denylist = properties.urlDenylist();
        if (denylist.isEmpty() || email.rawContent() == null) {
            return Optional.empty();
        }
        String text = new String(email.rawContent(), StandardCharsets.UTF_8);
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String host = hostOf(matcher.group(1));
            if (host != null && matchesDenylist(host, denylist)) {
                return Optional.of(new RuleMatch(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL));
            }
        }
        return Optional.empty();
    }

    /** Strips any userinfo and port, lower-cases, and returns the bare host. */
    private static String hostOf(String authority) {
        String host = authority;
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesDenylist(String host, List<String> denylist) {
        for (String entry : denylist) {
            String bad = entry.toLowerCase(Locale.ROOT);
            if (host.equals(bad) || host.endsWith("." + bad)) {
                return true;
            }
        }
        return false;
    }
}
