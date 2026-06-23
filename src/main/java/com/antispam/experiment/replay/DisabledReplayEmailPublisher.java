package com.antispam.experiment.replay;

import com.antispam.event.ReplayEmailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op {@link ReplayEmailPublisher} used when the event spine is switched off
 * ({@code app.kafka.enabled=false}) — tests and any environment without a broker.
 *
 * <p>Its existence is the point: {@code ReplayService} always receives a publisher and never has
 * to check whether Kafka is configured, mirroring {@code DisabledRawEmailPublisher}.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "false")
public class DisabledReplayEmailPublisher implements ReplayEmailPublisher {

    private static final Logger log = LoggerFactory.getLogger(DisabledReplayEmailPublisher.class);

    @Override
    public void publish(ReplayEmailEvent event) {
        log.debug("event spine disabled; not publishing emails.replay event for run={} id={}",
                event.runId(), event.emailId());
    }
}
