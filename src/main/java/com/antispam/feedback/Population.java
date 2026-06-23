package com.antispam.feedback;

import java.util.List;

/**
 * An assembled population of synthetic users (story 07.01): the concrete list of personas a
 * simulation run will act through, in the order they were drawn. The same {@link PopulationSpec}
 * always produces the same {@code members} (AC 5); 07.02 walks these against a decision stream.
 *
 * @param members the personas, one element per user, in deterministic draw order; never empty
 */
public record Population(List<Persona> members) {

    public Population {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("a population has at least one member");
        }
        members = List.copyOf(members);
    }

    /** Number of users in the population. */
    public int size() {
        return members.size();
    }
}
