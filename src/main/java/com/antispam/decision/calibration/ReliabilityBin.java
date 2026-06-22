package com.antispam.decision.calibration;

/**
 * One bucket of a reliability diagram: the predictions whose probability fell in
 * {@code [lowerEdge, upperEdge)} (the top bin includes 1.0), how many there were, the
 * mean probability the model assigned them, and the fraction that were actually
 * positive. A bin is well-calibrated when {@code meanPredicted ≈ observedFrequency};
 * the gap between the two, count-weighted across bins, is the expected calibration
 * error. These rows are the evidence surfaced per {@code model_version} (story 04.02
 * AC 3) — a viewer reads them as the reliability curve.
 *
 * @param lowerEdge         inclusive lower probability edge of the bin
 * @param upperEdge         exclusive upper edge (inclusive in the topmost bin)
 * @param count             number of predictions that fell in the bin
 * @param meanPredicted     mean predicted probability over the bin, or {@code 0} if empty
 * @param observedFrequency fraction of the bin that was actually positive, or {@code 0} if empty
 */
public record ReliabilityBin(
        double lowerEdge,
        double upperEdge,
        long count,
        double meanPredicted,
        double observedFrequency) {

    /** The unsigned reliability gap |predicted − observed| this bin contributes. */
    public double gap() {
        return Math.abs(meanPredicted - observedFrequency);
    }
}
