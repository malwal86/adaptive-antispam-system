package com.antispam.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection coordinates for the managed services the system depends on,
 * bound from environment variables / profiles under the {@code app} prefix:
 *
 * <ul>
 *   <li>{@code APP_POSTGRES_URL} — Supabase Postgres (source-of-truth store)</li>
 *   <li>{@code APP_REDIS_URL} — Upstash Redis (reputation cache, budget cap)</li>
 *   <li>{@code APP_KAFKA_BOOTSTRAP_SERVERS} — Aiven Kafka (event spine)</li>
 * </ul>
 *
 * <p>Each value is {@link NotBlank}: with {@code @Validated} this turns a
 * missing or whitespace-only setting into a startup failure, so the process
 * never boots half-configured (it would otherwise fail later, opaquely, on
 * first use). The values themselves are URLs/hostnames, never secrets — real
 * credentials arrive through the environment, never the repo.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class RequiredServicesProperties {

    @NotBlank(message = "app.postgres-url (env APP_POSTGRES_URL) must be set")
    private final String postgresUrl;

    @NotBlank(message = "app.redis-url (env APP_REDIS_URL) must be set")
    private final String redisUrl;

    @NotBlank(message = "app.kafka-bootstrap-servers (env APP_KAFKA_BOOTSTRAP_SERVERS) must be set")
    private final String kafkaBootstrapServers;

    public RequiredServicesProperties(String postgresUrl, String redisUrl, String kafkaBootstrapServers) {
        this.postgresUrl = postgresUrl;
        this.redisUrl = redisUrl;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public String getPostgresUrl() {
        return postgresUrl;
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }
}
