package com.antispam.eval.web;

import com.antispam.eval.GoldenSetVersion;
import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.util.Map;

/**
 * {@code POST /eval/golden/{version}} result: the provenance of the golden version just frozen plus
 * its class balance. The version, split configuration, and per-class counts are what make the frozen
 * benchmark identifiable in an eval report (story 11.02 AC 5) — a reviewer can see exactly which emails
 * the gate's precision will be measured against and that the snapshot is sensibly balanced.
 *
 * @param version      the frozen version's stable label
 * @param evalFraction the split fraction the snapshotted eval side was produced under
 * @param seed         the split seed it was produced under
 * @param total        emails frozen
 * @param ham          ham emails frozen
 * @param spam         spam emails frozen
 * @param phish        phish emails frozen
 * @param createdAt    when the version was frozen
 */
public record FreezeGoldenResponse(
        String version,
        double evalFraction,
        long seed,
        long total,
        long ham,
        long spam,
        long phish,
        Instant createdAt) {

    public static FreezeGoldenResponse from(GoldenSetVersion version, Map<GroundTruthLabel, Long> byLabel) {
        long ham = byLabel.getOrDefault(GroundTruthLabel.HAM, 0L);
        long spam = byLabel.getOrDefault(GroundTruthLabel.SPAM, 0L);
        long phish = byLabel.getOrDefault(GroundTruthLabel.PHISH, 0L);
        return new FreezeGoldenResponse(
                version.version(),
                version.evalFraction(),
                version.seed(),
                ham + spam + phish,
                ham,
                spam,
                phish,
                version.createdAt());
    }
}
