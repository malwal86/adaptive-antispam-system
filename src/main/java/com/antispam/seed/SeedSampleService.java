package com.antispam.seed;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Serves balanced labeled samples for the analyzer's sample picker. Clamps the
 * requested count to a sane range so a malformed query can neither ask for zero
 * nor drag the whole corpus back.
 */
@Service
public class SeedSampleService {

    /** Hard ceiling on samples-per-class the picker will return in one call. */
    static final int MAX_PER_LABEL = 25;

    private final SeedSampleRepository repository;

    @Autowired
    public SeedSampleService(SeedSampleRepository repository) {
        this.repository = repository;
    }

    /**
     * Up to {@code perLabel} samples of each ground-truth class. {@code perLabel}
     * is clamped to {@code [1, MAX_PER_LABEL]}.
     */
    public List<SeedSample> samples(int perLabel) {
        int clamped = Math.max(1, Math.min(perLabel, MAX_PER_LABEL));
        return repository.sample(clamped);
    }
}
