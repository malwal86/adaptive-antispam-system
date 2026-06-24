package com.antispam.retrain;

import com.antispam.decision.Probabilities;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The promotion gate's policy, bound from {@code antispam.retrain.gate} (story 10.03). A retrain
 * candidate is replayed over the frozen golden set and must clear {@code precisionFloor} before it
 * can ever be promoted (10.04) — precision is the <b>hard gate</b> because a false positive is blocked
 * good mail, the one failure the system must never regress into. Recall, bypass-rate, and cost are
 * reported as evidence but are deliberately <em>not</em> knobs here: they do not block.
 *
 * <p>The floor lives in config so it is one explicit, redeploy-free number rather than a magic
 * constant buried in the gate, the same way the calibration gate's {@code max-ece} does.
 *
 * @param precisionFloor    the minimum precision a candidate must reach on the golden set to pass, in
 *                          {@code [0,1]}; the floor is inclusive (a candidate that matches it has not
 *                          regressed below it). Set high — a false positive blocks good mail.
 * @param minGoldenSamples  the smallest golden set ({@code >= 1}) on which a precision measurement is
 *                          trusted; below it the gate fails rather than passing on noise (mirrors
 *                          calibration's {@code min-samples-per-side})
 */
@Validated
@ConfigurationProperties(prefix = "antispam.retrain.gate")
public record PrecisionGateProperties(double precisionFloor, @Min(1) int minGoldenSamples) {

    public PrecisionGateProperties {
        Probabilities.requireUnit("antispam.retrain.gate.precision-floor", precisionFloor);
    }
}
