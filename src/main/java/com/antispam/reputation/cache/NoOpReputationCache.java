package com.antispam.reputation.cache;

import com.antispam.reputation.CachedReputation;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The reputation cache when Redis is switched off (story 03.04) — the default for
 * local/dev and the Postgres-only tests. Every read is a miss and every write is
 * dropped, so the service always computes reputation from the Postgres event log. This
 * is the same behaviour the Redis cache degrades to on an outage, expressed as a bean so
 * the service never has to special-case "no cache configured".
 */
@Component
@ConditionalOnProperty(name = "antispam.reputation.cache.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpReputationCache implements ReputationReadCache {

    @Override
    public Optional<CachedReputation> get(String senderKey) {
        return Optional.empty();
    }

    @Override
    public void put(String senderKey, CachedReputation entry) {
        // Intentionally no-op: with the cache disabled, Postgres is the only store.
    }
}
