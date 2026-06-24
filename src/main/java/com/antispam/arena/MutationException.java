package com.antispam.arena;

/**
 * Thrown when the mutation engine cannot produce a valid variant from the requested seed (story
 * 08.01): the seed does not exist, has no ground-truth label, is not an abuse seed (this story
 * mutates spam to stress recall — mutating legit mail to stress precision is the two-track story
 * 08.03), or the attacker returned a degenerate result (blank, or identical to the seed — a
 * perturbation that changed nothing is not a usable attack). It is a caller/seed problem, distinct
 * from {@link AttackerUnavailableException}, which is the attacker itself being unreachable.
 */
public class MutationException extends RuntimeException {

    public MutationException(String message) {
        super(message);
    }
}
