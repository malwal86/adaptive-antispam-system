package com.antispam.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op {@link RawEmailPublisher} used when the event spine is switched off
 * ({@code app.kafka.enabled=false}) — tests and any environment without a broker.
 *
 * <p>Its existence is the point: {@code IngestService} always receives a publisher
 * and never has to check whether Kafka is configured, so the "no broker" case is
 * defined out of existence rather than handled at every call site.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "false")
public class DisabledRawEmailPublisher implements RawEmailPublisher {

    private static final Logger log = LoggerFactory.getLogger(DisabledRawEmailPublisher.class);

    @Override
    public void publish(RawEmailEvent event) {
        log.debug("event spine disabled; not publishing emails.raw event for id={}", event.emailId());
    }
}
