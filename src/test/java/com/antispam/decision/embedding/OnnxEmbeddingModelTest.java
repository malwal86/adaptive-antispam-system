package com.antispam.decision.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Unit checks for the in-process embedder, constructed directly (no Spring, no
 * database, and — the point of story 04.03 — no network). That the model is built
 * and queried with nothing but the bundled ONNX Runtime and the checked-in
 * artifact <em>is</em> the "zero per-embedding API cost" guarantee: there is no
 * HTTP client on this path to call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxEmbeddingModelTest {

    private OnnxEmbeddingModel model;

    @BeforeAll
    void setUp() {
        model = new OnnxEmbeddingModel();
    }

    @AfterAll
    void close() throws Exception {
        model.close();
    }

    @Test
    void embeds_to_the_fixed_dimension() {
        assertThat(model.embed("your order has shipped to Seattle"))
                .hasSize(OnnxEmbeddingModel.EMBEDDING_DIMENSION);
    }

    @Test
    void real_text_embeds_to_a_unit_vector() {
        // The graph L2-normalizes its output, so cosine similarity is a dot product.
        assertThat(norm(model.embed("your invoice of 49 dollars is ready"))).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void same_text_embeds_identically() {
        String text = "we noticed an unusual login to your account from a new device";
        assertThat(model.embed(text)).isEqualTo(model.embed(text));
    }

    @Test
    void token_free_text_embeds_to_the_zero_vector() {
        // No tokens → zero hashed input → zero embedding (the model can't invent a
        // direction from nothing); callers treat a zero vector as "no embedding".
        assertThat(norm(model.embed("!!! ???"))).isEqualTo(0.0);
    }

    @Test
    void different_topics_embed_to_different_vectors() {
        assertThat(model.embed("your package is out for delivery today"))
                .isNotEqualTo(model.embed("reset your password with this verification code"));
    }

    private static double norm(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }
}
