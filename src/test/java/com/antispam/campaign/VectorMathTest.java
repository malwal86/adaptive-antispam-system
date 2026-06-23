package com.antispam.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The vector arithmetic the clusterer is built on (story 06.03 unit plan: centroid
 * computation). Pins the definitions of cosine, normalization, and the normalized
 * centroid so the clustering behavior above it rests on a checked foundation.
 */
class VectorMathTest {

    @Test
    void cosine_of_identical_direction_is_one() {
        float[] a = {3f, 4f};
        float[] b = {6f, 8f}; // same direction, different magnitude
        assertThat(VectorMath.cosine(a, b)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosine_of_orthogonal_vectors_is_zero() {
        assertThat(VectorMath.cosine(new float[] {1f, 0f}, new float[] {0f, 1f}))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void cosine_of_opposite_vectors_is_minus_one() {
        assertThat(VectorMath.cosine(new float[] {1f, 0f}, new float[] {-1f, 0f}))
                .isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void cosine_with_a_zero_vector_is_zero_not_nan() {
        assertThat(VectorMath.cosine(new float[] {0f, 0f}, new float[] {1f, 1f})).isZero();
    }

    @Test
    void cosine_rejects_length_mismatch() {
        assertThatThrownBy(() -> VectorMath.cosine(new float[] {1f}, new float[] {1f, 2f}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalize_yields_unit_length_same_direction() {
        float[] unit = VectorMath.normalize(new float[] {3f, 4f});
        assertThat(unit[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(unit[1]).isCloseTo(0.8f, within(1e-6f));
        assertThat(VectorMath.cosine(unit, new float[] {3f, 4f})).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void normalize_of_zero_vector_stays_zero() {
        assertThat(VectorMath.normalize(new float[] {0f, 0f})).containsExactly(0f, 0f);
    }

    @Test
    void centroid_is_the_normalized_mean() {
        // Mean of (1,0) and (0,1) is (0.5,0.5); normalized that is (√2/2, √2/2).
        float[] c = VectorMath.centroid(List.of(new float[] {1f, 0f}, new float[] {0f, 1f}));
        float half = (float) (Math.sqrt(2) / 2);
        assertThat(c[0]).isCloseTo(half, within(1e-6f));
        assertThat(c[1]).isCloseTo(half, within(1e-6f));
    }

    @Test
    void centroid_of_a_single_vector_is_that_direction() {
        float[] c = VectorMath.centroid(List.of(new float[] {3f, 4f}));
        assertThat(VectorMath.cosine(c, new float[] {3f, 4f})).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void centroid_of_empty_set_is_rejected() {
        assertThatThrownBy(() -> VectorMath.centroid(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
