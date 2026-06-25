package com.antispam.decision.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns email text into the fixed-width vector of signed n-gram counts that the
 * local embedding model consumes (story 04.03). This is the Java half of a
 * cross-language contract: it must produce byte-identical vectors to
 * {@code ml/embedding_schema.py}'s {@code hashed_vector}, because the SVD inside
 * the ONNX graph was fit on vectors that module produced. The Java↔Python parity
 * test ({@code OnnxEmbeddingParityTest}) embeds the same texts on both sides and
 * fails loudly if the two hashings ever diverge.
 *
 * <p><b>What stays here vs. in the model.</b> Only the hashing lives in Java.
 * Everything after it — input L2 normalization, the learned projection, output
 * L2 normalization — is baked into the exported ONNX graph, so the serving path
 * reproduces just this deterministic function and reads a ready-made unit vector
 * out of the runtime.
 *
 * <p><b>Total and deterministic.</b> Like the feature extractor, this never
 * throws on bad input: {@code null} or text with no alphanumeric content yields
 * an all-zero vector (which the model maps to a zero embedding). The same text
 * always yields the same vector — the property dedupe and clustering depend on.
 */
final class TextHasher {

    /**
     * Width of the hashed n-gram space — the embedding model's input dimension.
     * Must equal {@code HASH_DIM} in {@code ml/embedding_schema.py}.
     */
    static final int DIMENSION = 4096;

    /**
     * Word n-gram sizes hashed into the vector. Unigrams carry topic; bigrams
     * carry local phrasing (so a reworded blast stays near its original). Must
     * equal {@code NGRAM_SIZES} in {@code ml/embedding_schema.py}.
     */
    private static final int[] NGRAM_SIZES = {1, 2};

    private TextHasher() {
    }

    /**
     * Hashes {@code text} into a {@link #DIMENSION}-length vector of signed n-gram
     * counts. For each word n-gram, {@link String#hashCode()} picks the bucket
     * ({@link Math#floorMod} into {@link #DIMENSION}) and its sign bit picks +1/-1;
     * the signed accumulation lets colliding n-grams cancel on average rather than
     * always inflating a bucket (the standard feature-hashing trick).
     *
     * @param text email text (subject + body); {@code null} is treated as empty
     * @return a new {@code float[DIMENSION]}; all zeros when the text has no tokens
     */
    static float[] hash(String text) {
        float[] vector = new float[DIMENSION];
        String[] tokens = tokenize(text);
        for (int n : NGRAM_SIZES) {
            for (int i = 0; i + n <= tokens.length; i++) {
                int h = gram(tokens, i, n).hashCode();
                vector[Math.floorMod(h, DIMENSION)] += h < 0 ? -1.0f : 1.0f;
            }
        }
        return vector;
    }

    /** Joins {@code n} tokens starting at {@code i} with single spaces (the n-gram key). */
    private static String gram(String[] tokens, int i, int n) {
        if (n == 1) {
            return tokens[i];
        }
        StringBuilder gram = new StringBuilder(tokens[i]);
        for (int k = 1; k < n; k++) {
            gram.append(' ').append(tokens[i + k]);
        }
        return gram.toString();
    }

    /**
     * Splits text into lowercase alphanumeric tokens. Deliberately ASCII-only and
     * a hand-written scan rather than a regex or {@link String#toLowerCase()}:
     * Unicode case-folding differs between Java and Python and this is half of a
     * cross-language contract. Runs of {@code [A-Za-z0-9]} become tokens ({@code A-Z}
     * lowercased by adding 0x20); every other character — including non-ASCII
     * letters — is a delimiter. Mirrors {@code embedding_schema.tokenize} exactly.
     */
    private static String[] tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                current.append((char) (c + 32));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                current.append(c);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }
}
