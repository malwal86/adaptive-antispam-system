package com.antispam.retrain;

import java.util.List;

/**
 * The assembled labeled-data training export (story 10.01): every training example combined from the
 * three label sources, plus the {@link #featureVersion} they were all stamped with. It is a
 * deterministic projection of the database — the same snapshot yields the same export (stable ordering,
 * no wall clock, no RNG) — so a retrain run is reproducible from a fixed snapshot and runnable from CI.
 *
 * @param featureVersion the feature schema version every example is tied to
 * @param examples       the training examples, in a stable order (seed first, then feedback/arena)
 */
public record TrainingExport(int featureVersion, List<TrainingExample> examples) {

    public TrainingExport {
        examples = List.copyOf(examples);
    }
}
