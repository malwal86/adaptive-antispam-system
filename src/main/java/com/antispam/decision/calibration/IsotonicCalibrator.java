package com.antispam.decision.calibration;

import com.antispam.decision.Probabilities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A non-parametric calibrator fit by <b>isotonic regression</b> — the method the PRD
 * names first for the model's hard calibration dependency (§Subsystem 2). It learns a
 * monotonic, non-decreasing step from raw score to observed frequency, so a higher
 * raw score never yields a lower calibrated probability. Isotonic is preferred over a
 * parametric Platt sigmoid here because it makes no shape assumption: it bends to
 * whatever miscalibration the model actually has, given enough held-out points.
 *
 * <p>The fit uses the standard <b>Pool Adjacent Violators</b> (PAV) algorithm, which
 * is the exact, deterministic minimiser of weighted squared error under the
 * monotonicity constraint. Between the fitted breakpoints {@link #calibrate} linearly
 * interpolates; below the first and above the last it clamps to the end values
 * (sklearn's {@code out_of_bounds='clip'}), so the result is always a probability in
 * {@code [0,1]} and defined for every input.
 */
public final class IsotonicCalibrator implements ProbabilityCalibrator {

    /** Sorted, strictly-ascending raw-score breakpoints. */
    private final double[] thresholds;

    /** Calibrated value at each breakpoint; non-decreasing, all in {@code [0,1]}. */
    private final double[] calibrated;

    private IsotonicCalibrator(double[] thresholds, double[] calibrated) {
        this.thresholds = thresholds;
        this.calibrated = calibrated;
    }

    /**
     * Fits a calibrator to held-out labeled scores.
     *
     * @param points the held-out points; must be non-empty
     * @return the fitted, monotonic calibrator
     * @throws IllegalArgumentException if {@code points} is null or empty
     */
    public static IsotonicCalibrator fit(Collection<LabeledScore> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("cannot fit a calibrator on no points");
        }

        // 1. Collapse exact-tie scores into one weighted block: a function can assign
        //    only one calibrated value per score, and pooling ties first keeps the PAV
        //    pass operating on strictly-ascending x.
        List<Block> blocks = collapseTies(points);

        // 2. Pool Adjacent Violators: walk left to right, merging any block whose mean
        //    dips below its predecessor's until the sequence of means is non-decreasing.
        List<Block> pooled = poolAdjacentViolators(blocks);

        // 3. Expand pooled means back onto each unique threshold.
        return materialize(blocks, pooled);
    }

    @Override
    public double calibrate(double rawProbability) {
        if (rawProbability <= thresholds[0]) {
            return calibrated[0];
        }
        int last = thresholds.length - 1;
        if (rawProbability >= thresholds[last]) {
            return calibrated[last];
        }
        // Binary-search the bracketing interval, then linearly interpolate within it.
        int hi = upperBound(rawProbability);
        int lo = hi - 1;
        double span = thresholds[hi] - thresholds[lo];
        double t = (rawProbability - thresholds[lo]) / span;
        return calibrated[lo] + t * (calibrated[hi] - calibrated[lo]);
    }

    /** The fitted breakpoints, ascending — exposed so a fit can be serialized/inspected. */
    public double[] thresholds() {
        return thresholds.clone();
    }

    /** The calibrated value at each breakpoint, parallel to {@link #thresholds()}. */
    public double[] calibratedValues() {
        return calibrated.clone();
    }

    private int upperBound(double x) {
        int lo = 1;
        int hi = thresholds.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (thresholds[mid] <= x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static List<Block> collapseTies(Collection<LabeledScore> points) {
        List<LabeledScore> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> Double.compare(a.score(), b.score()));
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            double x = sorted.get(i).score();
            double sum = 0.0;
            long weight = 0;
            while (i < sorted.size() && sorted.get(i).score() == x) {
                sum += sorted.get(i).positive() ? 1.0 : 0.0;
                weight++;
                i++;
            }
            blocks.add(new Block(x, sum / weight, weight));
        }
        return blocks;
    }

    private static List<Block> poolAdjacentViolators(List<Block> blocks) {
        List<Block> stack = new ArrayList<>();
        for (Block block : blocks) {
            Block merged = block;
            while (!stack.isEmpty() && stack.get(stack.size() - 1).mean >= merged.mean) {
                merged = stack.remove(stack.size() - 1).pooledWith(merged);
            }
            stack.add(merged);
        }
        return stack;
    }

    private static IsotonicCalibrator materialize(List<Block> uniqueThresholds, List<Block> pooled) {
        int n = uniqueThresholds.size();
        double[] thresholds = new double[n];
        double[] calibrated = new double[n];
        int idx = 0;
        for (Block pool : pooled) {
            // Each pooled block covers `span` consecutive thresholds, all sharing its mean.
            for (int k = 0; k < pool.span(); k++) {
                calibrated[idx] = Probabilities.clampUnit(pool.mean());
                idx++;
            }
        }
        for (int j = 0; j < n; j++) {
            thresholds[j] = uniqueThresholds.get(j).x();
        }
        return new IsotonicCalibrator(thresholds, calibrated);
    }

    /**
     * A contiguous run of points pooled to one calibrated value. {@code x} is the
     * smallest threshold the block represents (only meaningful before pooling);
     * {@code mean} is the weighted fraction positive; {@code weight} is the point
     * count; {@code span} is how many unique thresholds the block now covers.
     */
    private record Block(double x, double mean, long weight, int span) {

        Block(double x, double mean, long weight) {
            this(x, mean, weight, 1);
        }

        Block pooledWith(Block next) {
            long w = weight + next.weight;
            double pooledMean = (mean * weight + next.mean * next.weight) / w;
            return new Block(x, pooledMean, w, span + next.span);
        }
    }
}
