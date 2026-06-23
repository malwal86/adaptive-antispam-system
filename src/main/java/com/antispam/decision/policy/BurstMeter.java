package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Makes burst escalations observable (story 06.01 AC 5). A burst override fires regardless of the
 * model's posterior, so it is exactly the kind of decision an operator wants to see in aggregate —
 * a spike in this counter is the signature of a campaign hitting the system. The counter is tagged
 * with the tier the burst escalated to, so a flood of quarantines from velocity is distinguishable
 * from steady-state traffic.
 *
 * <p>Only created when {@code antispam.burst.enabled=true}, alongside the detector it instruments.
 */
@Component
@ConditionalOnProperty(name = "antispam.burst.enabled", havingValue = "true")
public class BurstMeter {

    /** Counter name; tagged {@code escalated-to=<tier>} — one increment per burst escalation. */
    static final String BURST_COUNTER = "antispam.burst.detected";

    private final MeterRegistry meters;

    @Autowired
    public BurstMeter(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Records one burst escalation to {@code escalatedTo}. */
    public void recordBurst(Decision escalatedTo) {
        meters.counter(BURST_COUNTER, "escalated-to", escalatedTo.name()).increment();
    }
}
