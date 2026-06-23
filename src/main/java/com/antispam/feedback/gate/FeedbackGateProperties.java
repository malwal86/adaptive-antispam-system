package com.antispam.feedback.gate;

import com.antispam.decision.Probabilities;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The feedback-gate policy, bound from {@code antispam.feedback.gate} (story 07.03). Feedback is an
 * attack surface (PRD §Subsystem 7), so these knobs — how far a malicious persona is down-weighted
 * and how much corroboration is required before feedback moves state — live in config, tunable
 * without a redeploy and explicit in one place, rather than scattered as magic numbers in the gate.
 *
 * <p>Defaults are chosen so good-faith corroboration clears the gate while a lone or malicious
 * report does not: with full-trust personas acting at typical confidence (~0.7), three independent
 * reporters sum to ~2.1 ≥ {@code minWeight} and meet {@code minCorroborators}; a single report meets
 * neither, and a clutch of {@code maliciousTrust}-weighted bombers stays under {@code minWeight}.
 *
 * @param maliciousTrust      trust placed in a malicious persona's feedback, in {@code [0,1]}; the
 *                            down-weight factor {@link FeedbackWeighting} applies (default 0.1)
 * @param minCorroborators    distinct personas required before a group is trusted ({@code >= 1});
 *                            the report-bomb defence — one persona can never clear it (default 3)
 * @param minWeight           aggregate weight required before a group is trusted ({@code > 0}); the
 *                            low-trust defence — down-weighted bombers sum below it (default 1.5)
 * @param maxReputationWeight cap on the weight of a single emitted reputation event ({@code > 0}),
 *                            so one corroborated run produces a measurable but <em>bounded</em>
 *                            state change (success metric) rather than an unbounded spike (default 5.0)
 */
@Validated
@ConfigurationProperties(prefix = "antispam.feedback.gate")
public record FeedbackGateProperties(
        double maliciousTrust,
        @Min(1) int minCorroborators,
        @Positive double minWeight,
        @Positive double maxReputationWeight) {

    public FeedbackGateProperties {
        Probabilities.requireUnit("antispam.feedback.gate.malicious-trust", maliciousTrust);
    }
}
