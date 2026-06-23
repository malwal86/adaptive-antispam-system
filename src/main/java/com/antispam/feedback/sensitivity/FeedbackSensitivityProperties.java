package com.antispam.feedback.sensitivity;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The sensitivity-sweep policy, bound from {@code antispam.feedback.sensitivity} (story 07.04).
 * {@code drift-tolerance} is the most reputation movement (in weight units) a sweep point may show
 * and still count as "blunted": below the gate's breakdown the bombers move state by exactly zero,
 * so a small tolerance leaves headroom for rounding without masking a real attack landing. Lives in
 * config so the acceptable-bound the demo asserts against is explicit and tunable, not a magic
 * number in the harness.
 *
 * @param driftTolerance maximum blunted reputation drift, in weight units ({@code >= 0})
 */
@Validated
@ConfigurationProperties(prefix = "antispam.feedback.sensitivity")
public record FeedbackSensitivityProperties(@PositiveOrZero double driftTolerance) {
}
