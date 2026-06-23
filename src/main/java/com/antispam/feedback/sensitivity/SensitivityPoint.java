package com.antispam.feedback.sensitivity;

/**
 * One point of the sensitivity curve (story 07.04): at a given malicious fraction, how much harmful
 * reputation each attack vector landed through the gate. The two drifts are one-directional attack
 * footprints in reputation-weight units, after the gate — not net movement, because a legit sender
 * legitimately earning GOOD from genuine clicks is the system working, not the attack:
 *
 * <ul>
 *   <li>{@code hamReputationDrift} — BAD weight the report bombers landed on the legitimate sender;
 *       should stay ~0 while the attack is blunted (AC 1);</li>
 *   <li>{@code spamPromotionDrift} — GOOD weight the rescue bombers landed on the spam sender;
 *       should stay ~0 while the attack is blunted (AC 2).</li>
 * </ul>
 *
 * {@code blunted} is true when both drifts are within the report's tolerance — the at-a-glance
 * "did the defence hold here?".
 *
 * @param maliciousFraction the fraction of the population that was malicious
 * @param populationSize    distinct personas at this point
 * @param bomberCount       distinct malicious identities (both vectors)
 * @param hamReputationDrift BAD weight the report bombers landed on the legitimate sender
 * @param spamPromotionDrift GOOD weight the rescue bombers landed on the spam sender
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
