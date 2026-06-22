package com.antispam.analyze;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns a machine decision ({@link Decision} + {@link ReasonCode}s) into one
 * short, human-readable sentence for the analyzer UI. Kept separate from
 * {@link ReasonCode} on purpose: the reason code stays a pure, closed machine
 * token (what a downstream LLM must choose from in Epic 05); this is the
 * presentation surface that renders it for a person.
 *
 * <p>The phrasing is <em>grounded</em> — every clause is derived from a reason
 * code that actually fired, never invented — which is the same "explainable, not
 * confidently-wrong" discipline the reason-code enum exists to enforce. The
 * {@code switch} over {@link ReasonCode} is exhaustive, so a new code added in a
 * later story (05.03) fails to compile here until it is given a phrase.
 */
public final class AnalysisExplainer {

    private AnalysisExplainer() {
    }

    /**
     * A one-sentence explanation of {@code decision} given the {@code codes} that
     * justified it.
     */
    public static String explain(Decision decision, List<ReasonCode> codes) {
        if (codes.isEmpty()) {
            return decision == Decision.ALLOW
                    ? "No hard rule fired; the model scored it as benign and the active policy "
                            + "allowed it."
                    : verdict(decision) + ", but no specific reason was recorded.";
        }
        String reasons = codes.stream().map(AnalysisExplainer::phrase).collect(Collectors.joining("; "));
        return verdict(decision) + ": " + reasons + ".";
    }

    private static String verdict(Decision decision) {
        return switch (decision) {
            case ALLOW -> "Allowed";
            case WARN -> "Delivered with a warning";
            case QUARANTINE -> "Quarantined";
            case BLOCK -> "Blocked";
        };
    }

    private static String phrase(ReasonCode code) {
        return switch (code) {
            case KNOWN_BAD_URL -> "a link resolves to a known-malicious host";
            case MALFORMED_AUTH_BRAND_SPOOF ->
                    "the message impersonates a high-value brand but fails authentication (DMARC)";
            case BURST_OVERRIDE ->
                    "it is part of a detected sending burst, which escalated the verdict";
            case SUSPICIOUS_LINK ->
                    "a link shows phishing tells (a raw-IP or punycode host, or a look-alike domain)";
            case CREDENTIAL_PHISHING ->
                    "it solicits credentials or imitates an account-security flow";
            case URGENCY_PRESSURE ->
                    "it uses manufactured urgency or pressure tactics to force a quick action";
            case PRIZE_OR_LOTTERY_BAIT ->
                    "it dangles a prize, lottery, or other financial windfall as bait";
            case UNSOLICITED_BULK ->
                    "it is unsolicited bulk or promotional content with no prior relationship";
            case SENDER_REPUTATION_RISK ->
                    "the sender's reputation is poor or too uncertain to trust";
            case BENIGN_CONTENT ->
                    "no abusive signal was found in the message";
        };
    }
}
