package com.antispam.feedback.sensitivity;

/**
 * One point of the sensitivity curve (story 07.04): at a given malicious fraction, how far the
 * bombers moved state through the gate. The two drifts are the harm each attack vector did, in
 * reputation-weight units, after the gate:
 *
 * <ul>
 *   <li>{@code hamReputationDrift} — net BAD weight the report bombers landed on the legitimate
 *       sender ({@code BAD − GOOD}); should stay ~0 while the attack is blunted (AC 1);</li>
 *   <li>{@code spamPromotionDrift} — net GOOD weight the rescue bombers landed on the spam sender
 *       ({@code GOOD − BAD}); should stay ~0 while the attack is blunted (AC 2).</li>
 * </ul>
 *
 * {@code blunted} is true when both drifts are within the report's tolerance — the at-a-glance
 * "did the defence hold here?".
 *
 * @param maliciousFraction the fraction of the population that was malicious
 * @param populationSize    distinct personas at this point
 * @param bomberCount       distinct malicious identities (both vectors)
 * @param hamReputationDrift net BAD weight on the legitimate sender after the gate
 * @param spamPromotionDrift net GOOD weight on the spam sender after the gate
 * @param blunted           whether both drifts stayed within tolerance
 */
public record SensitivityPoint(
        double maliciousFraction,
        int populationSize,
        int bomberCount,
        double hamReputationDrift,
        double spamPromotionDrift,
        boolean blunted) {
}
