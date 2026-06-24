package com.antispam.arena.web;

import com.antispam.arena.MutationStrategy;
import java.util.UUID;

/**
 * The body of {@code POST /arena/mutations}: which real seed spam to perturb and how. The seed must
 * be an existing abuse email in the corpus; an unknown or non-abuse seed is rejected with 400 (see
 * {@link ArenaExceptionHandler}).
 *
 * @param seedEmailId the {@code emails.id} of the real seed spam to mutate
 * @param strategy    the perturbation to apply
 */
public record MutateRequest(UUID seedEmailId, MutationStrategy strategy) {
}
