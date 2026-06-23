package com.antispam.experiment.replay;

import com.antispam.event.ReplayEmailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The experimental consumer for {@code emails.replay} (story 09.01): an independent consumer group
 * on a topic distinct from {@code emails.raw}, so a replay neither reads nor perturbs the live
 * stream — its offsets, lag, and decisions are entirely separate from production processing (AC 4).
 *
 * <p>Each record is scored under the policy baked into the event ({@link ReplayScoringService}) and
 * recorded to {@code replay_decisions}, never to live {@code classifications}. As with the live
 * consumers, an unexpected per-record error is caught and logged so the offset still advances and
 * one bad record cannot wedge the shard — the corpus is durable, so the replay can be re-triggered.
 *
 * <p>Only active when the spine is enabled ({@code app.kafka.enabled=true}); off by default in
 * tests and any environment without a broker.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReplayConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReplayConsumer.class);

    private final ReplayScoringService scoringService;

    @Autowired
    public ReplayConsumer(ReplayScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @KafkaListener(
            topics = "${app.kafka.replay-topic.name:emails.replay}",
            groupId = "${app.kafka.replay-consumer.group-id:replay-experimental}",
            concurrency = "${app.kafka.replay-consumer.concurrency:6}")
    public void onReplay(ReplayEmailEvent event) {
        try {
            scoringService.score(event);
        } catch (Exception e) {
            log.error("replay scoring failed run={} id={}; skipping record",
                    event.runId(), event.emailId(), e);
        }
    }
}
