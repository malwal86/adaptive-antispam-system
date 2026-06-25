package com.antispam.eval.web;

import com.antispam.seed.GroundTruthLabel;
import java.util.Map;

/**
 * {@code GET /eval/fresh} (and the result of appending to it) summary: the class balance of the rolling
 * fresh challenge set. It is reported separately from the golden set so a viewer can watch the fresh
 * set grow with the latest attacks without that growth ever touching the frozen golden baseline (story
 * 11.02).
 *
 * @param total emails in the fresh set
 * @param ham   ham emails
 * @param spam  spam emails
 * @param phish phish emails
 */
public record FreshSetResponse(long total, long ham, long spam, long phish) {

    public static FreshSetResponse from(Map<GroundTruthLabel, Long> byLabel) {
        long ham = byLabel.getOrDefault(GroundTruthLabel.HAM, 0L);
        long spam = byLabel.getOrDefault(GroundTruthLabel.SPAM, 0L);
        long phish = byLabel.getOrDefault(GroundTruthLabel.PHISH, 0L);
        return new FreshSetResponse(ham + spam + phish, ham, spam, phish);
    }
}
