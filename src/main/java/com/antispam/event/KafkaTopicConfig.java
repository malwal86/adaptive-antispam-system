package com.antispam.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares {@code emails.raw} so Spring's {@code KafkaAdmin} provisions it on
 * startup with the configured partition count, replication, and retention
 * (story 02.01 acceptance criterion 4). Declaring the topology in code keeps it
 * versioned and documented rather than relying on broker auto-creation, which is
 * commonly disabled on managed brokers like Aiven.
 *
 * <p>Only active when the spine is enabled; when off, no admin client runs.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    /**
     * The {@code emails.raw} topic. Retention is set explicitly so the spine's
     * replay window is a documented value, not a broker default.
     */
    @Bean
    public NewTopic emailsRawTopic(KafkaTopicProperties properties) {
        KafkaTopicProperties.RawTopic raw = properties.rawTopic();
        return TopicBuilder.name(raw.name())
                .partitions(raw.partitions())
                .replicas(raw.replicationFactor())
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(raw.retention().toMillis()))
                .build();
    }
}
