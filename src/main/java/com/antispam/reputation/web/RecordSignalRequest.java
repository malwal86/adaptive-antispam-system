package com.antispam.reputation.web;

import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.reputation.AuthGate;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationSignal;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /senders/{id}/reputation/events}: a single reputation signal to
 * record (stories 03.01, 03.03). The sender is the path id, so only the signal and its
 * authentication context travel in the body.
 *
 * <p>{@code signal} is the one required field. {@code weight} and {@code source} are
 * optional with sensible defaults ({@code 1.0} and {@code "api"}). The auth tokens
 * {@code spf}/{@code dkim}/{@code dmarc} are optional and feed the soft auth-gate: the
 * controller routes the signal to the authenticated or unauthenticated bucket via
 * {@link AuthGate}. Omitting them (no auth context) is treated as <b>unauthenticated</b>
 * — the safe default — so a caller must assert {@code dmarc: "pass"} to accrue trust,
 * which is exactly the warmed-up-domain demo beat.
 *
 * @param signal whether the signal is good or bad; required
 * @param weight how much it counts; {@code null} means full weight (1.0)
 * @param source provenance; {@code null}/blank means {@code "api"}
 * @param spf    SPF result token (e.g. {@code pass}); {@code null} means not asserted
 * @param dkim   DKIM result token; {@code null} means not asserted
 * @param dmarc  DMARC result token; {@code dmarc=pass} is what earns the authenticated bucket
 */
public record RecordSignalRequest(
        @NotNull ReputationSignal signal,
        Double weight,
        String source,
        String spf,
        String dkim,
        String dmarc) {

    /** The weight to record, defaulting an omitted value to full weight. */
    public double weightOrDefault() {
        return weight == null ? 1.0 : weight;
    }

    /** The source to record, defaulting an omitted/blank value to {@code "api"}. */
    public String sourceOrDefault() {
        return (source == null || source.isBlank()) ? "api" : source;
    }

    /**
     * The accrual bucket these auth tokens earn. Missing tokens degrade to
     * {@code "unknown"} (the {@code AuthFeatures} sentinel), so a request with no auth
     * context routes to {@link ReputationBucket#UNAUTHENTICATED}.
     */
    public ReputationBucket bucket() {
        return AuthGate.bucketFor(new AuthFeatures(
                tokenOrUnknown(spf), tokenOrUnknown(dkim), tokenOrUnknown(dmarc)));
    }

    private static String tokenOrUnknown(String token) {
        return (token == null || token.isBlank()) ? "unknown" : token;
    }
}
