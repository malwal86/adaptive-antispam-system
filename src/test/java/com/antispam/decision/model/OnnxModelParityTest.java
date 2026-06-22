package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.ModelScores;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Export-fidelity check (story 04.01 AC 2): for each fixed input vector, the score
 * the Java ONNX Runtime produces must match the score the Python source model
 * produced at export time, within numerical tolerance. The fixture
 * {@code parity-cases.json} is written by {@code ml/train_classifier.py} from the
 * very model checked into {@code src/main/resources/models}, so a regenerated model
 * that drifts from its fixture — or a Java serving bug — fails here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxModelParityTest {

    /** Java vs Python run the same ONNX graph; a thousandth is generous headroom. */
    private static final double TOLERANCE = 1e-3;

    private OnnxModel model;
    private List<ParityCase> cases;

    record ParityCase(String name, float[] features, double hamScore,
            double spamScore, double phishingScore) {
    }

    @BeforeAll
    void setUp() throws Exception {
        model = new OnnxModel();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/models/parity-cases.json")) {
            assertThat(in).as("parity fixture must be on the test classpath").isNotNull();
            cases = List.of(mapper.readValue(in, ParityCase[].class));
        }
    }

    @AfterAll
    void close() throws Exception {
        model.close();
    }

    @Test
    void fixture_is_not_empty() {
        assertThat(cases).isNotEmpty();
    }

    @Test
    void java_scores_match_the_python_reference_within_tolerance() {
        for (ParityCase c : cases) {
            ModelScores scores = model.score(c.features());
            assertThat(scores.spamScore())
                    .as("spam score for case '%s'", c.name())
                    .isCloseTo(c.spamScore(), org.assertj.core.data.Offset.offset(TOLERANCE));
            assertThat(scores.phishingScore())
                    .as("phishing score for case '%s'", c.name())
                    .isCloseTo(c.phishingScore(), org.assertj.core.data.Offset.offset(TOLERANCE));
        }
    }
}
