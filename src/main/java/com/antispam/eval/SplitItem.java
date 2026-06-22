package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.util.UUID;

/**
 * One labeled email as the splitter sees it: just enough to group it, time-order
 * it, and stratify by class. Deliberately decoupled from where the signals come
 * from — the bootstrap source (story 11.01) supplies the sender domain as the
 * {@code groupId} family proxy, but a later enrichment (Epic 06 campaign clusters,
 * Epic 08 arena lineage) can supply a real lineage id without the splitter changing.
 *
 * @param emailId the email this item identifies
 * @param groupId the family the email belongs to (campaign/lineage proxy), or null
 *                when the email has no known family — the splitter then treats it as
 *                its own singleton group
 * @param time    the email's position on the timeline, used for the time-forward
 *                holdout; the source resolves this (received-at, falling back to
 *                ingested-at) so the splitter never sees a null
 * @param label   the ground-truth class; only high-confidence corpus labels reach
 *                here, never simulator feedback (story 11.03)
 */
public record SplitItem(UUID emailId, String groupId, Instant time, GroundTruthLabel label) {

    public SplitItem {
        if (emailId == null) {
            throw new IllegalArgumentException("emailId is required");
        }
        if (time == null) {
            throw new IllegalArgumentException("time is required (the source resolves a fallback)");
        }
        if (label == null) {
            throw new IllegalArgumentException("label is required");
        }
    }
}
