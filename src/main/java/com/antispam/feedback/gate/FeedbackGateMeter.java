package com.antispam.feedback.gate;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Makes the feedback gate's defence observable (story 07.03). The point of the gate is that
 * feedback is an attack surface, so the number that proves it is working is the fraction of
 * candidate groups it <em>blocks</em> — a report-bombing campaign should show up as a spike in
 * blocked groups, not as moved state. This meter records, for every corroboration group the gate
 * considers, whether it cleared the gate, and counts what actually reached each sink.
 *
 * <p>It exposes:
 * <ul>
 *   <li>a counter {@code antispam.feedback.gate.group} tagged {@code trusted=true|false} and
 *       {@code signal}, so the blocked fraction is {@code false / (true + false)} — directly
 *       queryable, and attributable to GOOD vs BAD corroboration;</li>
 *   <li>counters {@code antispam.feedback.gate.reputation.emitted} and
 *       {@code antispam.feedback.gate.label.emitted}, the rows each sink received, so the two-sink
 *       fan-out (AC 4) is visible.</li>
 * </ul>
 */
@Component
public class FeedbackGateMeter {

    /** Counter name; tagged {@code trusted=true|false} and {@code signal}. Blocked fraction = false / total. */
    static final String GROUP_COUNTER = "antispam.feedback.gate.group";

    /** Counter name; reputation events appended to the reputation sink (one per trusted group). */
    static final String REPUTATION_COUNTER = "antispam.feedback.gate.reputation.emitted";

    /** Counter name; retrain labels written to the label sink (one per item in a trusted group). */
    static final String LABEL_COUNTER = "antispam.feedback.gate.label.emitted";

    private final MeterRegistry meters;

    @Autowired
    public FeedbackGateMeter(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Records the gate's verdict on one corroboration group. */
    public void recordGroup(CorroborationKey key, boolean trusted) {
        meters.counter(GROUP_COUNTER, "trusted", Boolean.toString(trusted), "signal", key.signal().name())
                .increment();
    }

    /** Records one reputation event reaching the reputation sink. */
    public void recordReputationEmitted() {
        meters.counter(REPUTATION_COUNTER).increment();
    }

    /** Records {@code count} retrain labels reaching the label sink. */
    public void recordLabelsEmitted(int count) {
        meters.counter(LABEL_COUNTER).increment(count);
    }
}
