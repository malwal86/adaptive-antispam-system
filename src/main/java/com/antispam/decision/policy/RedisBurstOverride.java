package com.antispam.decision.policy;

import com.antispam.decision.ReasonCode;
import com.antispam.event.SenderKey;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The runtime burst-override detector (stories 06.01, 06.02): the velocity half of the
 * warm-then-attack defence (time decay, Epic 03, is the other half). It escalates a decision
 * regardless of posterior (PRD §Subsystem 1 step 4) on either of two runtime signals over the same
 * sliding window:
 * <ul>
 *   <li><b>Sender velocity</b> (06.01): one sender's message count in the window exceeds the active
 *       policy's {@link Policy#burstThreshold()} — a warmed-up sender suddenly blasting.</li>
 *   <li><b>Content near-duplication</b> (06.02): a cluster of near-duplicate content
 *       ({@link SimHasher} + {@link NearDuplicateIndex}) exceeds the same threshold — a templated
 *       campaign, even when spread across many senders to dodge per-sender velocity.</li>
 * </ul>
 * Selected over {@link NoBurstOverride} when {@code antispam.burst.enabled=true}.
 *
 * <p><b>Escalation is a floor, not a verdict.</b> It returns an {@link Escalation} to the configured
 * {@link BurstProperties#escalateTo()} tier; {@link PolicyDecisionService} takes the more severe of
 * that and the posterior-derived tier, so a burst can only raise severity. The
 * {@link ReasonCode#BURST_OVERRIDE} reason is recorded only when the override actually changes the
 * tier; the {@link BurstTrigger} that fired is recorded on the metric for attribution.
 *
 * <p><b>Degrade open on outage.</b> Unlike the LLM budget, which fails closed because the risk is
 * runaway <em>spend</em>, the burst counters are an additive safety signal on top of the posterior.
 * Failing closed here would mass-escalate legitimate mail to quarantine whenever Redis blips — a
 * self-inflicted denial of service. So a Redis error is swallowed and treated as "no burst": the
 * posterior-derived tier stands, exactly as when burst detection is disabled.
 */
@Component
@ConditionalOnProperty(name = "antispam.burst.enabled", havingValue = "true")
public class RedisBurstOverride implements BurstOverride {

    private static final Logger log = LoggerFactory.getLogger(RedisBurstOverride.class);

    // Versioned per-sender key prefix so the window layout can evolve without colliding with old
    // counters (a bumped version simply starts fresh windows).
    static final String KEY_PREFIX = "burst:v1:sender:";

    private final SlidingWindowCounter senderWindow;
    private final NearDuplicateIndex nearDuplicateIndex;
    private final SimHasher simHasher;
    private final BurstProperties properties;
    private final BurstMeter meter;
    private final Clock clock;

    @Autowired
    public RedisBurstOverride(
            SlidingWindowCounter senderWindow,
            NearDuplicateIndex nearDuplicateIndex,
            SimHasher simHasher,
            BurstProperties properties,
            BurstMeter meter,
            Clock clock) {
        this.senderWindow = senderWindow;
        this.nearDuplicateIndex = nearDuplicateIndex;
        this.simHasher = simHasher;
        this.properties = properties;
        this.meter = meter;
        this.clock = clock;
    }

    @Override
    public Optional<Escalation> evaluate(Email email, Policy policy) {
        String senderKey = senderKeyOf(email);
        String emailId = email.id().toString();
        Instant now = clock.instant();

        Optional<BurstTrigger> trigger;
        try {
            long senderCount = senderWindow.recordAndCount(
                    KEY_PREFIX + senderKey, emailId, now, properties.window());
            long nearDupCount = nearDuplicateCount(email, emailId, now);
            trigger = firstTripped(senderCount, nearDupCount, policy.burstThreshold());
        } catch (RuntimeException e) {
            // No burst signal available: degrade open so a Redis outage never mass-escalates mail.
            log.warn("burst check failed against Redis for sender={}, not escalating: {}",
                    senderKey, e.toString());
            return Optional.empty();
        }

        if (trigger.isPresent()) {
            meter.recordBurst(properties.escalateTo(), trigger.get());
            log.info("burst escalation sender={} trigger={} threshold={} escalateTo={}",
                    senderKey, trigger.get(), policy.burstThreshold(), properties.escalateTo());
            return Optional.of(new Escalation(properties.escalateTo(), ReasonCode.BURST_OVERRIDE));
        }
        return Optional.empty();
    }

    /**
     * The size of this email's near-duplicate cluster in the window, or 0 when the body has no content
     * fingerprint (token-free) — so blank bodies are never grouped as duplicates of one another.
     */
    private long nearDuplicateCount(Email email, String emailId, Instant now) {
        long fingerprint = simHasher.fingerprint(EmailFeatureExtractor.displayText(email.rawContent()));
        if (fingerprint == 0L) {
            return 0L;
        }
        return nearDuplicateIndex.recordAndCountCluster(fingerprint, emailId, now);
    }

    /**
     * The trigger that first exceeded the threshold, sender velocity taking precedence over content
     * near-duplication when both fired, or empty when neither did. "Exceeds" is strict: a count equal
     * to the threshold is not yet a burst.
     */
    private static Optional<BurstTrigger> firstTripped(
            long senderCount, long nearDupCount, int threshold) {
        if (senderCount > threshold) {
            return Optional.of(BurstTrigger.SENDER_VELOCITY);
        }
        if (nearDupCount > threshold) {
            return Optional.of(BurstTrigger.CONTENT_NEAR_DUP);
        }
        return Optional.empty();
    }

    private static String senderKeyOf(Email email) {
        ParsedEmail metadata = email.metadata();
        return SenderKey.of(metadata.sender(), metadata.senderDomain());
    }
}
