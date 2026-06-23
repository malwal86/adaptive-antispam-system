package com.antispam.experiment.replay;

import com.antispam.event.ReplayEmailEvent;

/**
 * Publishes a {@link ReplayEmailEvent} onto the {@code emails.replay} topic so the experimental
 * consumer can score it (story 09.01). The mirror of {@code RawEmailPublisher} for the replay
 * spine: keyed by sender so a sender's replayed mail co-locates on one partition, exactly as on
 * {@code emails.raw}.
 *
 * <p>There is always exactly one bean: the Kafka-backed publisher when the spine is enabled, a
 * no-op stand-in otherwise — so {@code ReplayService} never branches on whether Kafka is wired.
 */
public interface ReplayEmailPublisher {

    /**
     * Publishes one email of a replay run. Must not throw on a transient broker problem —
     * delivery is retried/surfaced by the implementation, not propagated into the trigger call,
     * since the corpus is durable and a replay can simply be re-triggered.
     */
    void publish(ReplayEmailEvent event);
}
