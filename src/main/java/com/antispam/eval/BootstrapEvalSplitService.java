package com.antispam.eval;

import com.antispam.seed.GroundTruthLabel;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds and materializes the bootstrap train/eval split (stories 11.01 / 11.03):
 * read the labeled corpus, split it grouped and time-forward, and persist the
 * assignment so calibration (04.02) and export (10.01) can query a leakage-free
 * partition. Rebuilding is deterministic — the same corpus and configuration always
 * produce the same split — so it is safe to re-run after the corpus grows.
 */
@Service
public class BootstrapEvalSplitService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapEvalSplitService.class);

    private final BootstrapCorpusRepository corpus;
    private final GroupedTimeForwardSplitter splitter;
    private final EvalSplitRepository assignments;
    private final EvalSplitProperties properties;

    @Autowired
    public BootstrapEvalSplitService(
            BootstrapCorpusRepository corpus,
            GroupedTimeForwardSplitter splitter,
            EvalSplitRepository assignments,
            EvalSplitProperties properties) {
        this.corpus = corpus;
        this.splitter = splitter;
        this.assignments = assignments;
        this.properties = properties;
    }

    /**
     * Recomputes the split over the whole labeled corpus and replaces the stored
     * assignment with it.
     *
     * @return the leakage-free audit of the split just written
     */
    public SplitAudit rebuild() {
        EvalSplit split = splitter.split(corpus.loadCorpus(), properties.toSplitConfig());
        assignments.replaceAll(split);
        SplitAudit audit = split.audit();
        log.info("eval split rebuilt: train={} eval={} groups={} crossBoundary={} temporalInversions={}",
                audit.trainCount(), audit.evalCount(), audit.groupCount(),
                audit.crossBoundaryGroups(), audit.temporalInversions());
        return audit;
    }

    /** Per-(side, class) counts of the currently stored split. */
    public Map<SplitSide, Map<GroundTruthLabel, Long>> currentCountsByLabel() {
        return assignments.countsBySideAndLabel();
    }

    /** The configuration the next rebuild would use. */
    public EvalSplitProperties configuration() {
        return properties;
    }
}
