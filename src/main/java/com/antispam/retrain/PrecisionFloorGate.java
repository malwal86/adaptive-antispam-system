package com.antispam.retrain;

import com.antispam.experiment.replay.PolicyMetrics;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The precision-floor promotion gate (story 10.03): the hard rule that a retrain candidate must clear
 * a fixed precision floor on the frozen golden set before it can ever be promoted. This is the whole
 * point of the slow living loop's safety — a retrain "can never silently degrade precision (block good
 * mail)," so precision is the only auto-blocker; recall, bypass-rate, and cost are computed and
 * reported as {@link GateEvidence} but never change the verdict (PRD §Subsystem 9).
 *
 * <p>The gate is a pure function of the candidate's {@link PolicyMetrics} scorecard and the configured
 * {@link PrecisionGateProperties}: given the same golden replay it always returns the same verdict
 * (AC 5). It deliberately knows nothing about <em>how</em> the scorecard was produced (the golden-set
 * replay grading lives in {@link PrecisionGateService}); it only judges the numbers, which keeps the
 * one decision that matters — pass/fail — trivial to read and to test.
 *
 * <p>Two ways a candidate fails: precision below the floor (the headline guard), or a golden set too
 * small for precision to mean anything. The second is not in the acceptance criteria's arithmetic but
 * is the same honesty the calibration gate enforces with {@code min-samples-per-side}: a precision of
 * 1.0 over three emails is noise, and passing on it would let a retrain through on an unreliable
 * measurement. Both failures are distinguished in the verdict's {@code reason}.
 */
@Component
public class PrecisionFloorGate {

    private final PrecisionGateProperties properties;

    @Autowired
    public PrecisionFloorGate(PrecisionGateProperties properties) {
        this.properties = properties;
    }

    /**
     * Judges a candidate's golden-set scorecard against the precision floor.
     *
     * @param candidate the candidate's metrics on the frozen golden set (from the replay path)
     * @return the pass/fail verdict, the precision-vs-floor it turned on, and the reported evidence
     */
    public GateResult evaluate(PolicyMetrics candidate) {
        double floor = properties.precisionFloor();
        long samples = candidate.total();
        GateEvidence evidence = GateEvidence.from(candidate);

        if (samples < properties.minGoldenSamples()) {
            return verdict(false, candidate, evidence, String.format(Locale.ROOT,
                    "golden set too small to judge precision: %d < %d minimum samples",
                    samples, properties.minGoldenSamples()));
        }

        boolean passed = candidate.precision() >= floor;
        String reason = passed
                ? String.format(Locale.ROOT,
                        "precision %.4f >= floor %.4f on %d golden emails", candidate.precision(), floor, samples)
                : String.format(Locale.ROOT,
                        "precision %.4f < floor %.4f on %d golden emails", candidate.precision(), floor, samples);
        return verdict(passed, candidate, evidence, reason);
    }

    private GateResult verdict(
            boolean passed, PolicyMetrics candidate, GateEvidence evidence, String reason) {
        return new GateResult(
                passed,
                candidate.precision(),
                properties.precisionFloor(),
                candidate.total(),
                candidate.policyVersion(),
                candidate.modelVersion(),
                evidence,
                reason);
    }
}
