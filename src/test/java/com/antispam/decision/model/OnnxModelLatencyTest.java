package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Story 04.01 AC 4: in-process inference must leave the synchronous pipeline within
 * its &lt;100ms budget. This micro-benchmark measures single-vector scoring (warmed
 * up to exclude class-load / first-run allocation) and asserts the p95 is a small
 * fraction of that budget. The threshold is deliberately generous so the test is a
 * regression guard against an order-of-magnitude blow-up (e.g. reloading the model
 * per call), not a flaky exact-timing assertion on shared CI hardware.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxModelLatencyTest {

    private static final int WARMUP = 50;
    private static final int MEASURED = 200;

    /** Generous ceiling: ≤ half the 100ms fast-path budget, for one inference. */
    private static final long P95_BUDGET_MS = 50;

    private OnnxModel model;
    private float[] vector;

    @BeforeAll
    void setUp() {
        model = new OnnxModel();
        // A representative, fully-populated vector; content is irrelevant to timing.
        vector = new float[ModelFeatureVector.FEATURE_COUNT];
        Arrays.fill(vector, 1.0f);
    }

    @AfterAll
    void close() throws Exception {
        model.close();
    }

    @Test
    void single_inference_p95_is_well_within_the_fast_path_budget() {
        for (int i = 0; i < WARMUP; i++) {
            model.score(vector);
        }

        long[] timingsNanos = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            model.score(vector);
            timingsNanos[i] = System.nanoTime() - start;
        }

        Arrays.sort(timingsNanos);
        long p95Millis = timingsNanos[(int) (MEASURED * 0.95)] / 1_000_000L;
        assertThat(p95Millis)
                .as("p95 single-inference latency (ms)")
                .isLessThanOrEqualTo(P95_BUDGET_MS);
    }
}
