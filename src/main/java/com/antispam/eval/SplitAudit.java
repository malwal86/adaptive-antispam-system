package com.antispam.eval;

import java.time.Instant;

/**
 * The evidence that a split is leakage-free, computed by re-checking the finished
 * assignment rather than trusting the algorithm that produced it — so a regression
 * in the splitter surfaces as a non-zero count here instead of a silently dishonest
 * eval. This is the heart of Epic 11: the numbers a reviewer can point at to say
 * "this measurement isn't inflated".
 *
 * @param total                 emails assigned (train + eval)
 * @param trainCount            emails on the train side
 * @param evalCount             emails held out for eval
 * @param groupCount            distinct families (after the singleton rule)
 * @param crossBoundaryGroups   families with members on both sides — MUST be 0
 *                              (story 11.01); any other value is a grouping bug
 * @param temporalInversions    eval emails older than the newest train email — the
 *                              honest count of time-forward leakage (story 11.03);
 *                              0 when families are time-contiguous, surfaced rather
 *                              than hidden when they overlap
 * @param latestTrainTime       newest email on the train side, or null if train is
 *                              empty — the wall the eval set should sit beyond
 * @param earliestEvalTime      oldest email on the eval side, or null if eval is empty
 */
public record SplitAudit(
        int total,
        int trainCount,
        int evalCount,
        int groupCount,
        int crossBoundaryGroups,
        int temporalInversions,
        Instant latestTrainTime,
        Instant earliestEvalTime) {

    /** Whether the split is leakage-free: no family straddles, no eval email precedes train. */
    public boolean leakageFree() {
        return crossBoundaryGroups == 0 && temporalInversions == 0;
    }
}
