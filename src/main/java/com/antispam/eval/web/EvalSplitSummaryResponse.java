package com.antispam.eval.web;

import com.antispam.eval.EvalSplitProperties;
import com.antispam.eval.SplitSide;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /eval/split} result: the class balance of the currently stored split,
 * per side, plus the configuration a rebuild would use. Lets a viewer confirm the
 * held-out set exists and is sensibly balanced without re-running the split.
 *
 * @param evalFraction the held-out share configured
 * @param seed         the tie-break seed configured
 * @param sides        per-side counts; an absent side means the table has no rows yet
 */
public record EvalSplitSummaryResponse(double evalFraction, long seed, List<SideCounts> sides) {

    /**
     * Per-side class counts.
     *
     * @param side  {@code train} or {@code eval}
     * @param total emails on this side
     * @param ham   ham emails on this side
     * @param spam  spam emails on this side
     * @param phish phish emails on this side
     */
    public record SideCounts(String side, long total, long ham, long spam, long phish) {

        private static SideCounts of(SplitSide side, Map<GroundTruthLabel, Long> byLabel) {
            long ham = byLabel.getOrDefault(GroundTruthLabel.HAM, 0L);
            long spam = byLabel.getOrDefault(GroundTruthLabel.SPAM, 0L);
            long phish = byLabel.getOrDefault(GroundTruthLabel.PHISH, 0L);
            return new SideCounts(side.dbValue(), ham + spam + phish, ham, spam, phish);
        }
    }

    public static EvalSplitSummaryResponse from(
            Map<SplitSide, Map<GroundTruthLabel, Long>> countsByLabel, EvalSplitProperties config) {
        List<SideCounts> sides = countsByLabel.entrySet().stream()
                .map(e -> SideCounts.of(e.getKey(), e.getValue()))
                .toList();
        return new EvalSplitSummaryResponse(config.evalFraction(), config.seed(), sides);
    }
}
