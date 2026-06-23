package com.antispam.decision.policy;

import com.antispam.decision.ReasonCode;
import com.antispam.event.SenderKey;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The runtime burst-override detector (story 06.01): the velocity half of the warm-then-attack
 * defence (time decay, Epic 03, is the other half). Each email records a hit in a per-sender Redis
 * sliding window ({@link SlidingWindowCounter}); when a sender's count within the window exceeds the
 * active policy's {@link Policy#burstThreshold()}, the decision is escalated regardless of posterior
 * (PRD §Subsystem 1 step 4), so a warmed-up sender launching a sudden blast is caught even when each
 * individual message looks borderline. Selected over {@link NoBurstOverride} when
 * {@code antispam.burst.enabled=true}.
 *
 * <p><b>Escalation is a floor, not a verdict.</b> It returns an {@link Escalation} to the configured
 * {@link BurstProperties#escalateTo()} tier; {@link PolicyDecisionService} takes the more severe of
 * that and the posterior-derived tier, so a burst can only raise severity, and a strong score is
 * never softened. The {@link ReasonCode#BURST_OVERRIDE} reason is recorded only when the override
 * actually changes the tier.
 *
 * <p><b>Degrade open on outage.</b> Unlike the LLM budget, which fails closed because the risk is
 * runaway <em>spend</em>, the burst counter is an additive safety signal on top of the posterior.
 * The risk of failing closed here would be mass-escalating legitimate mail to quarantine whenever
 * Redis blips — a self-inflicted denial of service. So a Redis error is swallowed and treated as
 * "no burst": the posterior-derived tier stands, exactly as when burst detection is disabled.
 */
@Component
@ConditionalOnProperty(name = "antispam.burst.enabled", havingValue = "true")
public class RedisBurstOverride implements BurstOverride {

    private static final Logger log = LoggerFactory.getLogger(RedisBurstOverride.class);

    // Versioned per-sender key prefix so the window layout can evolve without colliding with old
    // counters (a bumped version simply starts fresh windows).
    static final String KEY_PREFIX = "burst:v1:sender:";

    private final SlidingWindowCounter window;
    private final BurstProperties properties;
    private final BurstMeter meter;
    private final Clock clock;

    @Autowired
    public RedisBurstOverride(
            SlidingWindowCounter window, BurstProperties properties, BurstMeter meter, Clock clock) {
        this.window = window;
        this.properties = properties;
        this.meter = meter;
        this.clock = clock;
    }

    @Override
    public Optional<Escalation> evaluate(Email email, Policy policy) {
        String senderKey = senderKeyOf(email);
        long count;
        try {
            count = window.recordAndCount(
                    KEY_PREFIX + senderKey, email.id().toString(), clock.instant(), properties.window());
        } catch (RuntimeException e) {
            // No burst signal available: degrade open so a Redis outage never mass-escalates mail.
            log.warn("burst window check failed against Redis for sender={}, not escalating: {}",
                    senderKey, e.toString());
            return Optional.empty();
        }

        if (count > policy.burstThreshold()) {
            meter.recordBurst(properties.escalateTo());
            log.info("burst escalation sender={} count={} threshold={} escalateTo={}",
                    senderKey, count, policy.burstThreshold(), properties.escalateTo());
            return Optional.of(new Escalation(properties.escalateTo(), ReasonCode.BURST_OVERRIDE));
        }
        return Optional.empty();
    }

    private static String senderKeyOf(Email email) {
        ParsedEmail metadata = email.metadata();
        return SenderKey.of(metadata.sender(), metadata.senderDomain());
    }
}
