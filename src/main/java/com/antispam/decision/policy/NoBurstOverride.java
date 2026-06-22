package com.antispam.decision.policy;

import com.antispam.ingest.Email;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The default {@link BurstOverride}: never escalates, so until Epic 06's burst detector
 * lands the tier is decided purely by the active policy's thresholds. It exists so the
 * pipeline has a non-null collaborator to call from day one (Ousterhout: define the error
 * out of existence) — story 06.01 swaps in the real detector with no change to the
 * decision path.
 */
@Component
public class NoBurstOverride implements BurstOverride {

    @Override
    public Optional<Escalation> evaluate(Email email) {
        return Optional.empty();
    }
}
