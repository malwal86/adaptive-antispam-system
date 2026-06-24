package com.antispam.retrain;

import com.antispam.features.EmailFeatureExtractor;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Assembles the labeled-data training export (story 10.01, PRD §Subsystem 9 step 1): the first step of
 * the slow living loop. It pulls the three label sources — seed ground truth, weighted simulator
 * feedback (07.03), and arena ground truth (08.04) — into one training-ready stream, every example
 * carrying its label, weight, provenance, and the feature schema version it is tied to, with the golden
 * eval set excluded so nothing held out for judging can leak into training (Epic 11).
 *
 * <p>The export is a deterministic projection of the database: the sources are read in a fixed order
 * (seed first, then feedback/arena) under stable, tiebroken {@code ORDER BY}s, and every example is
 * stamped with {@link EmailFeatureExtractor#FEATURE_VERSION} (a constant, not the wall clock), so the
 * same DB snapshot always yields the same export — reproducible and runnable from CI (AC 5).
 */
@Service
public class LabeledDataExportService {

    private static final Logger log = LoggerFactory.getLogger(LabeledDataExportService.class);

    private final LabeledDataExportRepository repository;

    @Autowired
    public LabeledDataExportService(LabeledDataExportRepository repository) {
        this.repository = repository;
    }

    /**
     * Builds the full training export at the current feature version. Seed labels come first, then the
     * feedback and arena labels, each block already in a stable order — so the combined export is itself
     * deterministic.
     */
    public TrainingExport export() {
        int featureVersion = EmailFeatureExtractor.FEATURE_VERSION;
        List<TrainingExample> examples = new ArrayList<>();
        examples.addAll(repository.exportSeedLabels(featureVersion));
        examples.addAll(repository.exportFeedbackAndArenaLabels(featureVersion));
        log.info("labeled-data export: {} examples at featureVersion={}", examples.size(), featureVersion);
        return new TrainingExport(featureVersion, examples);
    }
}
