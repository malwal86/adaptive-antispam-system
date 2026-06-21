package com.antispam.reputation;

import com.antispam.features.FeatureSet.AuthFeatures;
import java.util.Locale;

/**
 * Routes a reputation signal to its accrual {@link ReputationBucket} from the email's
 * authentication result (story 03.03). This is the whole of the soft-gate's "which
 * bucket?" decision, kept pure and in one place so the rule is auditable and unit-
 * testable without a database.
 *
 * <p>The rule mirrors the alignment semantics the hard-rule layer already uses
 * (com.antispam.decision.hardrule.BrandSpoofAuthRule): a sender is treated as
 * authenticated <em>only</em> on an explicit {@code dmarc=pass}. DMARC is the
 * alignment authority — it asserts that an SPF- or DKIM-passing identifier actually
 * aligns with the visible From domain — so SPF/DKIM passing on their own, or DMARC
 * being absent/failing, leaves the mail unauthenticated and unable to inherit a
 * domain's earned trust.
 */
public final class AuthGate {

    private AuthGate() {
    }

    /**
     * The bucket a signal from mail with these auth results accrues into:
     * {@link ReputationBucket#AUTHENTICATED} iff DMARC explicitly passed, otherwise
     * {@link ReputationBucket#UNAUTHENTICATED}. Null auth (no auth context at all) is
     * treated as unauthenticated — the safe default.
     */
    public static ReputationBucket bucketFor(AuthFeatures auth) {
        return dmarcAligned(auth) ? ReputationBucket.AUTHENTICATED : ReputationBucket.UNAUTHENTICATED;
    }

    /** Whether these auth results assert full DMARC alignment ({@code dmarc=pass}). */
    public static boolean dmarcAligned(AuthFeatures auth) {
        return auth != null
                && auth.dmarc() != null
                && auth.dmarc().toLowerCase(Locale.ROOT).equals("pass");
    }
}
