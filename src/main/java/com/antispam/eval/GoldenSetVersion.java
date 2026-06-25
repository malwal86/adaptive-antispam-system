package com.antispam.eval;

import java.time.Instant;

/**
 * The provenance of one frozen golden benchmark (story 11.02): its stable label, the split
 * configuration the eval side it snapshotted was produced under, how many emails it froze, and when.
 * A version is immutable once written — this record is a read of {@code golden_set_versions}, never a
 * mutable handle — so the gate (10.03) can pin to a {@code version} and measure a candidate's
 * precision against the exact same emails across model versions.
 *
 * @param version      the stable label the gate pins to
 * @param evalFraction the held-out share the snapshotted eval side was produced under
 * @param seed         the tie-break seed that eval side was produced under
 * @param memberCount  how many emails the snapshot froze
 * @param createdAt    when the version was frozen
 */
public record GoldenSetVersion(
        String version, double evalFraction, long seed, int memberCount, Instant createdAt) {
}
