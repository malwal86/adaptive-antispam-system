package com.antispam.reputation.web;

import com.antispam.reputation.ReputationSignal;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /senders/{id}/reputation/events}: a single reputation signal
 * to record (story 03.01). The sender is the path id, so only the signal itself
 * travels in the body.
 *
 * <p>{@code signal} is the one required field. {@code weight} and {@code source} are
 * optional with sensible defaults applied by the controller ({@code 1.0} and
 * {@code "api"}), so the common case — "this sender just did something good/bad" —
 * is a one-field post; auth-gating (story 03.03) and the feedback path (Epic 07)
 * supply the richer values.
 *
 * @param signal whether the signal is good or bad; required
 * @param weight how much it counts; {@code null} means full weight (1.0)
 * @param source provenance; {@code null}/blank means {@code "api"}
 */
public record RecordSignalRequest(
        @NotNull ReputationSignal signal,
        Double weight,
        String source) {

    /** The weight to record, defaulting an omitted value to full weight. */
    public double weightOrDefault() {
        return weight == null ? 1.0 : weight;
    }

    /** The source to record, defaulting an omitted/blank value to {@code "api"}. */
    public String sourceOrDefault() {
        return (source == null || source.isBlank()) ? "api" : source;
    }
}
