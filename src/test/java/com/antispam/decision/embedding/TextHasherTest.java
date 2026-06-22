package com.antispam.decision.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit checks for the text→hashed-vector half of the embedding contract. The
 * cross-language agreement with {@code ml/embedding_schema.py} is proven
 * end-to-end by {@code OnnxEmbeddingParityTest}; here we pin the local invariants
 * the contract rests on: fixed width, determinism, totality, and the exact
 * bucket/sign a single token lands in.
 */
class TextHasherTest {

    @Test
    void hashes_to_the_fixed_input_dimension() {
        assertThat(TextHasher.hash("your order has shipped")).hasSize(TextHasher.DIMENSION);
    }

    @Test
    void same_text_hashes_identically() {
        String text = "Security alert: a new sign in from London was detected.";
        assertThat(TextHasher.hash(text)).isEqualTo(TextHasher.hash(text));
    }

    @Test
    void tokenization_is_case_insensitive() {
        assertThat(TextHasher.hash("HELLO WORLD")).isEqualTo(TextHasher.hash("hello world"));
    }

    @Test
    void null_text_hashes_to_all_zeros() {
        assertThat(TextHasher.hash(null)).containsOnly(0.0f);
    }

    @Test
    void text_with_no_alphanumeric_tokens_hashes_to_all_zeros() {
        assertThat(TextHasher.hash("!!! ??? ... ***")).containsOnly(0.0f);
    }

    @Test
    void different_text_produces_a_different_vector() {
        assertThat(TextHasher.hash("your invoice is ready"))
                .isNotEqualTo(TextHasher.hash("your order has shipped"));
    }

    @Test
    void a_single_token_sets_exactly_its_one_hashed_bucket() {
        // One token → one unigram, no bigrams → exactly one nonzero entry, at
        // floorMod(hashCode, DIMENSION), signed by the hash's sign bit. This is the
        // bucketing rule embedding_schema.hashed_vector must match.
        int hash = "newsletter".hashCode();
        int expectedBucket = Math.floorMod(hash, TextHasher.DIMENSION);
        float expectedSign = hash < 0 ? -1.0f : 1.0f;

        float[] vector = TextHasher.hash("newsletter");

        assertThat(vector[expectedBucket]).isEqualTo(expectedSign);
        float magnitude = 0.0f;
        for (float v : vector) {
            magnitude += Math.abs(v);
        }
        assertThat(magnitude).as("only the one bucket is set").isEqualTo(1.0f);
    }

    @Test
    void bigrams_make_word_order_matter() {
        // Same unigrams, different order → different bigrams → different vectors.
        assertThat(TextHasher.hash("alpha beta")).isNotEqualTo(TextHasher.hash("beta alpha"));
    }
}
