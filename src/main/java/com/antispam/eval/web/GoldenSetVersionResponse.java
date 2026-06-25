package com.antispam.eval.web;

import com.antispam.eval.GoldenSetVersion;
import java.time.Instant;

/**
 * One row of {@code GET /eval/golden}: a frozen golden version's identity and size, newest first. The
 * list is how an eval report enumerates the available benchmarks and the gate operator picks the
 * {@code version} to pin a candidate's precision measurement to (story 11.02).
 *
 * @param version      the version's stable label
 * @param evalFraction the split fraction its eval side was produced under
 * @param seed         the split seed its eval side was produced under
 * @param memberCount  how many emails it froze
 * @param createdAt    when it was frozen
 */
public record GoldenSetVersionResponse(
        String version, double evalFraction, long seed, int memberCount, Instant createdAt) {

    public static GoldenSetVersionResponse from(GoldenSetVersion version) {
        return new GoldenSetVersionResponse(
                version.version(),
                version.evalFraction(),
                version.seed(),
                version.memberCount(),
                version.createdAt());
    }
}
