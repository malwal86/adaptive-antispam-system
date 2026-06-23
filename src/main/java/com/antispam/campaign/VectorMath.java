package com.antispam.campaign;

import java.util.List;

/**
 * The small amount of vector arithmetic offline campaign clustering needs:
 * cosine similarity, L2 normalization, and the normalized centroid (mean) of a
 * group of vectors. Kept as a tiny, dependency-free, fully-tested utility so the
 * clusterer (and its tests) reason about one definition of "how similar" and "the
 * middle of a cluster" rather than re-deriving dot products inline.
 *
 * <p>Embeddings produced by {@code OnnxEmbeddingModel} are already L2-normalized,
 * so for them cosine similarity is just the dot product; {@link #cosine} still
 * divides by the norms so it is correct for any input (and so a centroid built
 * from a single member is handled the same as one built from many).
 */
final class VectorMath {

    private VectorMath() {
    }

    /**
     * Cosine similarity of two equal-length vectors, in {@code [-1, 1]}. Returns
     * {@code 0.0} if either vector is all-zero (no direction to compare).
     *
     * @throws IllegalArgumentException if the lengths differ
     */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "vector length mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Returns a new vector pointing the same way as {@code v} but with unit length.
     * An all-zero vector has no direction and is returned as a zero copy.
     */
    static float[] normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        float[] out = new float[v.length];
        if (norm == 0.0) {
            return out;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] * inv);
        }
        return out;
    }

    /**
     * The L2-normalized mean of the given vectors — the cluster centroid. All
     * vectors must share one width; the centroid is normalized so it lives in the
     * same unit-length space as the members and cosine against it is meaningful.
     *
     * @throws IllegalArgumentException if {@code vectors} is empty or widths differ
     */
    static float[] centroid(List<float[]> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("centroid of an empty set is undefined");
        }
        int dim = vectors.get(0).length;
        double[] sum = new double[dim];
        for (float[] v : vectors) {
            if (v.length != dim) {
                throw new IllegalArgumentException(
                        "vector length mismatch in centroid: " + v.length + " vs " + dim);
            }
            for (int i = 0; i < dim; i++) {
                sum[i] += v[i];
            }
        }
        float[] mean = new float[dim];
        for (int i = 0; i < dim; i++) {
            mean[i] = (float) (sum[i] / vectors.size());
        }
        return normalize(mean);
    }
}
