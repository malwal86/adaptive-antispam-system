package com.antispam.decision.policy;

import com.antispam.ingest.Email;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@link BurstOverride} when burst detection is switched off ({@code antispam.burst.enabled}
 * unset or {@code false}) — the default for local/dev and the Postgres-only tests, which have no
 * Redis to keep the sliding-window counters. It never escalates, so the tier is decided purely by
 * the active policy's thresholds. Expressed as a bean (Ousterhout: define the error out of
 * existence) so the pipeline always has a non-null collaborator to call; {@link RedisBurstOverride}
 * takes its place with no change to the decision path when burst detection is enabled.
 */
@Component
@ConditionalOnProperty(name = "antispam.burst.enabled", havingValue = "false", matchIfMissing = true)
public class NoBurstOverride implements BurstOverride {

    @Override
    public Optional<Escalation> evaluate(Email email, Policy policy) {
        return Optional.empty();
    }
}
