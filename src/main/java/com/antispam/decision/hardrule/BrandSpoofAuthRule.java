package com.antispam.decision.hardrule;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.hardrule.HardRuleProperties.Brand;
import com.antispam.ingest.Email;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Quarantines mail that claims a high-value brand but whose authentication does
 * not back the claim. The classic phish: a sender domain that name-drops a brand
 * (e.g. {@code paypal.account-verify.com}) while DMARC is failing or absent.
 *
 * <p>A message "claims" a brand when its sender domain contains the brand token
 * or is the brand's own domain. The claim is <em>legitimate</em> only when the
 * sender domain actually is the brand's domain (or a subdomain) <em>and</em>
 * DMARC passes; any other combination is treated as a spoof. This is
 * deliberately aggressive — quarantine, not block — and is the worst outcome a
 * hard rule reaches without a confirmed-bad URL.
 */
@Component
@Order(20)
public class BrandSpoofAuthRule implements HardRule {

    private final HardRuleProperties properties;

    @Autowired
    public BrandSpoofAuthRule(HardRuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<RuleMatch> evaluate(Email email) {
        String senderDomain = email.metadata().senderDomain();
        if (senderDomain == null) {
            return Optional.empty();
        }
        String domain = senderDomain.toLowerCase(Locale.ROOT);
        boolean dmarcPass = dmarcPass(email.metadata().authResults());
        for (Brand brand : properties.highValueBrands()) {
            if (claimsBrand(domain, brand) && !isLegitimate(domain, brand, dmarcPass)) {
                return Optional.of(new RuleMatch(Decision.QUARANTINE, ReasonCode.MALFORMED_AUTH_BRAND_SPOOF));
            }
        }
        return Optional.empty();
    }

    private static boolean claimsBrand(String domain, Brand brand) {
        return domain.contains(brand.name().toLowerCase(Locale.ROOT)) || isBrandDomain(domain, brand);
    }

    private static boolean isLegitimate(String domain, Brand brand, boolean dmarcPass) {
        return isBrandDomain(domain, brand) && dmarcPass;
    }

    private static boolean isBrandDomain(String domain, Brand brand) {
        return Hosts.isHostOrSubdomainOf(domain, brand.domain().toLowerCase(Locale.ROOT));
    }

    /** DMARC is considered aligned only on an explicit {@code dmarc=pass} result. */
    private static boolean dmarcPass(String authResults) {
        return authResults != null
                && authResults.toLowerCase(Locale.ROOT).contains("dmarc=pass");
    }
}
