package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds and reads the two eval sets of story 11.02: the frozen golden benchmark and the rolling fresh
 * challenge set. It is the seam between the leakage-free split (the source of the held-out eval side)
 * and the promotion gate (the consumer of the golden set): freezing snapshots the current eval side
 * into an immutable, versioned benchmark the gate can measure precision against comparably across model
 * versions, while reported attacks accumulate separately in the fresh set so they never disturb that
 * baseline.
 *
 * <p>Immutability is enforced twice over: the database rejects any change to a frozen version (V32
 * triggers), and this service refuses to re-freeze a label that already exists, turning the
 * lower-level constraint violation into a clear domain error before the write is attempted.
 */
@Service
public class EvalSetService {

    private static final Logger log = LoggerFactory.getLogger(EvalSetService.class);

    private final GoldenSetRepository goldenSets;
    private final FreshChallengeRepository freshSet;
    private final EvalSplitProperties splitProperties;

    @Autowired
    public EvalSetService(
            GoldenSetRepository goldenSets,
            FreshChallengeRepository freshSet,
            EvalSplitProperties splitProperties) {
        this.goldenSets = goldenSets;
        this.freshSet = freshSet;
        this.splitProperties = splitProperties;
    }

    /**
     * Freezes the current held-out eval side into a new immutable golden version.
     *
     * @param version the new version's stable label
     * @return the frozen version's provenance (label, split config, member count)
     * @throws IllegalArgumentException if a version with this label is already frozen — a frozen
     *         benchmark is never redefined (immutability), so the caller must choose a new label
     */
    public GoldenSetVersion freezeGolden(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("golden version label is required");
        }
        if (goldenSets.versionExists(version)) {
            throw new IllegalArgumentException(
                    "golden version '" + version + "' is already frozen and cannot be redefined");
        }
        GoldenSetVersion frozen =
                goldenSets.freeze(version, splitProperties.evalFraction(), splitProperties.seed());
        log.info("golden set frozen: version={} members={} evalFraction={} seed={}",
                frozen.version(), frozen.memberCount(), frozen.evalFraction(), frozen.seed());
        return frozen;
    }

    /** Every frozen golden version, newest first — the benchmark list an eval report cites. */
    public List<GoldenSetVersion> goldenVersions() {
        return goldenSets.findAll();
    }

    /** Per-class balance of one frozen golden version. */
    public Map<GroundTruthLabel, Long> goldenCountsByLabel(String version) {
        return goldenSets.countsByLabel(version);
    }

    /**
     * Appends a reported attack to the rolling fresh challenge set. Writes only the fresh set — never
     * the frozen golden set — so the comparable baseline is undisturbed (story 11.02 AC 3).
     */
    public void addFreshChallenge(UUID emailId, GroundTruthLabel label, String source) {
        freshSet.add(emailId, label, source);
        log.info("fresh challenge appended: email={} label={} source={}", emailId, label, source);
    }

    /** Per-class size of the rolling fresh set. */
    public Map<GroundTruthLabel, Long> freshCountsByLabel() {
        return freshSet.countsByLabel();
    }
}
