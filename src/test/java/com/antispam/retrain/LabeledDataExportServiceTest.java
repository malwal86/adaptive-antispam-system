package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.antispam.features.EmailFeatureExtractor;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The export service's assembly contract (story 10.01), pinned with a mocked repository so it needs no
 * database: it stamps every example with the current feature version, reads both sources at that
 * version, and concatenates them seed-first into one deterministic stream. The leakage filtering and
 * weight/provenance carry-through live in the repository SQL and are proven in the integration test.
 */
@ExtendWith(MockitoExtension.class)
class LabeledDataExportServiceTest {

    @Mock
    private LabeledDataExportRepository repository;

    @Test
    void stamps_the_current_feature_version_and_combines_the_sources_seed_first() {
        int fv = EmailFeatureExtractor.FEATURE_VERSION;
        TrainingExample seed = example("seed", 1.0, fv);
        TrainingExample feedback = example("feedback", 0.7, fv);
        TrainingExample arena = example("arena", 1.0, fv);
        when(repository.exportSeedLabels(fv)).thenReturn(List.of(seed));
        when(repository.exportFeedbackAndArenaLabels(fv)).thenReturn(List.of(feedback, arena));

        TrainingExport export = new LabeledDataExportService(repository).export();

        // The export is tied to the current feature schema, and the seed block precedes the
        // feedback/arena block so the combined stream is itself deterministic.
        assertThat(export.featureVersion()).isEqualTo(fv);
        assertThat(export.examples()).containsExactly(seed, feedback, arena);
    }

    @Test
    void produces_an_empty_export_when_there_are_no_labels() {
        int fv = EmailFeatureExtractor.FEATURE_VERSION;
        when(repository.exportSeedLabels(fv)).thenReturn(List.of());
        when(repository.exportFeedbackAndArenaLabels(fv)).thenReturn(List.of());

        TrainingExport export = new LabeledDataExportService(repository).export();

        assertThat(export.examples()).isEmpty();
        assertThat(export.featureVersion()).isEqualTo(fv);
    }

    private static TrainingExample example(String source, double weight, int featureVersion) {
        return new TrainingExample(UUID.randomUUID(), GroundTruthLabel.SPAM, weight, source,
                "{\"source\":\"" + source + "\"}", featureVersion);
    }
}
