package com.antispam.decision.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Cross-language export-fidelity check (story 04.03 AC 1–2): for each fixed text,
 * the embedding the Java path produces — Java tokenizing + hashing, then the
 * shared ONNX graph — must match the embedding {@code ml/train_embedding.py}
 * produced from the very model checked into {@code src/main/resources/models}. The
 * fixture is written by embedding the same texts through the exported graph in
 * Python, so this guards both halves at once: a drift in the Java/Python hashing
 * contract <em>or</em> a serving bug fails here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxEmbeddingParityTest {

    /** Java vs Python run the same ONNX graph; a thousandth is generous headroom. */
    private static final double TOLERANCE = 1e-3;

    private OnnxEmbeddingModel model;
    private List<ParityCase> cases;

    record ParityCase(String name, String text, float[] embedding) {
    }

    @BeforeAll
    void setUp() throws Exception {
        model = new OnnxEmbeddingModel();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/models/embedding-parity-cases.json")) {
            assertThat(in).as("embedding parity fixture must be on the test classpath").isNotNull();
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
    void java_embeddings_match_the_python_reference_within_tolerance() {
        for (ParityCase c : cases) {
            float[] actual = model.embed(c.text());
            assertThat(actual).as("dimension for case '%s'", c.name()).hasSameSizeAs(c.embedding());
            for (int i = 0; i < actual.length; i++) {
                assertThat((double) actual[i])
                        .as("case '%s' component %d", c.name(), i)
                        .isCloseTo(c.embedding()[i], Offset.offset(TOLERANCE));
            }
        }
    }
}
