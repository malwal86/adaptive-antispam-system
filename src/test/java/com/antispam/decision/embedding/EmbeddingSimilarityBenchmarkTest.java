package com.antispam.decision.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Semantic sanity benchmark (story 04.03 success metric): similar texts must score
 * high cosine similarity and dissimilar texts low, otherwise the embedding is
 * useless for the dedupe and clustering it exists to feed. Thresholds carry margin
 * around the measured values (a reworded same-topic pair scores ~0.94, a
 * cross-topic pair ~0.11 on this bootstrap model), so the test asserts the
 * <em>separation</em> holds, not exact numbers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddingSimilarityBenchmarkTest {

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
    void identical_text_has_cosine_similarity_one() {
        float[] e = model.embed("your order 1234 has shipped and is on its way to Austin");
        assertThat(cosine(e, e)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void reworded_same_topic_text_is_highly_similar() {
        // Same shipping topic, different order id and city — a near-duplicate.
        float[] a = model.embed("your order 1234 has shipped and is on its way to Austin");
        float[] b = model.embed("your order 5678 has shipped and is on its way to Berlin");
        assertThat(cosine(a, b)).isGreaterThan(0.85);
    }

    @Test
    void cross_topic_text_is_dissimilar() {
        // Shipping notice vs a promotional discount blast — unrelated topics.
        float[] shipping = model.embed("your order 1234 has shipped and is on its way to Austin");
        float[] promo = model.embed("limited time offer take 40 percent off your next order today");
        assertThat(cosine(shipping, promo)).isLessThan(0.5);
    }

    @Test
    void near_duplicate_ranks_above_cross_topic() {
        float[] anchor = model.embed("security alert a new sign in to your account from London was detected");
        float[] sameTopic = model.embed("security alert an unusual sign in to your account from Tokyo was detected");
        float[] otherTopic = model.embed("your invoice of 49 dollars is now available to download");
        assertThat(cosine(anchor, sameTopic)).isGreaterThan(cosine(anchor, otherTopic));
    }

    /** Embeddings are L2-normalized, so cosine similarity is their dot product. */
    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
