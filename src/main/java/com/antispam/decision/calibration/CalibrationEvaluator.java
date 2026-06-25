package com.antispam.decision.calibration;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures how honest a set of probabilities is — the reliability evidence the story
 * demands (04.02 AC 1, AC 3). It bins predictions by probability and compares, in each
 * bin, the mean predicted probability against the fraction that were actually positive.
 * The headline number is the <b>Expected Calibration Error</b> (ECE): the count-weighted
 * average of those per-bin gaps. ECE near zero means the scores can be read as true
 * frequencies; a large ECE means the model is over- or under-confident.
 *
 * <p>Pure and deterministic — it is the same measurement applied to raw scores and to
 * calibrated scores, so the two are directly comparable.
 */
public final class CalibrationEvaluator {

    private CalibrationEvaluator() {
    }

    /**
     * One scored prediction with the ground truth it was scoring.
     *
     * @param predicted the probability assigned, in {@code [0,1]}
     * @param actual    whether the predicted event actually occurred
     */
    public record Prediction(double predicted, boolean actual) {

        public Prediction {
            if (predicted < 0.0 || predicted > 1.0 || Double.isNaN(predicted)) {
                throw new IllegalArgumentException(
                        "predicted must be a probability in [0,1] but was " + predicted);
            }
        }
    }

    /**
     * The reliability diagram: {@code binCount} equal-width bins over {@code [0,1]},
     * each carrying its count, mean predicted probability, and observed positive
     * frequency. Empty bins are included (with zero count) so the curve has a fixed
     * shape regardless of the data.
     *
     * @throws IllegalArgumentException if {@code binCount < 1} or {@code predictions} is null
     */
    public static List<ReliabilityBin> reliabilityCurve(List<Prediction> predictions, int binCount) {
        if (predictions == null) {
            throw new IllegalArgumentException("predictions must not be null");
        }
        if (binCount < 1) {
            throw new IllegalArgumentException("binCount must be at least 1 but was " + binCount);
        }

        long[] counts = new long[binCount];
        long[] positives = new long[binCount];
        double[] predictedSum = new double[binCount];
        for (Prediction p : predictions) {
            int bin = binOf(p.predicted(), binCount);
            counts[bin]++;
            predictedSum[bin] += p.predicted();
            if (p.actual()) {
                positives[bin]++;
            }
        }

        List<ReliabilityBin> bins = new ArrayList<>(binCount);
        double width = 1.0 / binCount;
        for (int b = 0; b < binCount; b++) {
            long n = counts[b];
            double meanPredicted = n == 0 ? 0.0 : predictedSum[b] / n;
            double observed = n == 0 ? 0.0 : (double) positives[b] / n;
            bins.add(new ReliabilityBin(b * width, (b + 1) * width, n, meanPredicted, observed));
        }
        return bins;
    }

    /**
     * The Expected Calibration Error: the count-weighted average of each bin's
     * |meanPredicted − observedFrequency|. Returns {@code 0} for an empty set (no
     * predictions, no error to attribute).
     *
     * @throws IllegalArgumentException if {@code binCount < 1} or {@code predictions} is null
     */
    public static double expectedCalibrationError(List<Prediction> predictions, int binCount) {
        if (predictions == null) {
            throw new IllegalArgumentException("predictions must not be null");
        }
        if (predictions.isEmpty()) {
            return 0.0;
        }
        double total = predictions.size();
        double ece = 0.0;
        for (ReliabilityBin bin : reliabilityCurve(predictions, binCount)) {
            ece += (bin.count() / total) * bin.gap();
        }
        return ece;
    }

    /**
     * Bin index for a probability: {@code floor(p * binCount)}, with {@code p == 1.0}
     * folded into the top bin so the upper edge is inclusive there.
     */
    private static int binOf(double p, int binCount) {
        int bin = (int) (p * binCount);
        return bin == binCount ? binCount - 1 : bin;
    }
}
