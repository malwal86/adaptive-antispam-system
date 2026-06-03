package com.antispam.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies fail-fast behavior: the app must refuse to start when a required
 * managed-service URL (Postgres/Redis/Kafka) is missing or blank, rather than
 * booting half-configured.
 */
class RequiredServicesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void context_starts_when_all_required_service_urls_are_present() {
        runner.withPropertyValues(
                        "app.postgres-url=jdbc:postgresql://db:5432/antispam",
                        "app.redis-url=redis://cache:6379",
                        "app.kafka-bootstrap-servers=broker:9092")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void context_fails_to_start_when_postgres_url_is_missing() {
        runner.withPropertyValues(
                        "app.redis-url=redis://cache:6379",
                        "app.kafka-bootstrap-servers=broker:9092")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The offending field surfaces in the validation failure cause chain.
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("postgres-url");
                });
    }

    @Test
    void context_fails_to_start_when_a_required_url_is_blank() {
        runner.withPropertyValues(
                        "app.postgres-url=jdbc:postgresql://db:5432/antispam",
                        "app.redis-url=   ",
                        "app.kafka-bootstrap-servers=broker:9092")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(RequiredServicesProperties.class)
    static class TestConfig {
    }
}
