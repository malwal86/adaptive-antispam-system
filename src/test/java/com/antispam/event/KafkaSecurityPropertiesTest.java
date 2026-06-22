package com.antispam.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the Kafka security contract wired in {@code application.yml}. The
 * hosted deployment talks to a managed broker (Aiven) over SASL_SSL, while
 * local dev, tests, and the Testcontainers spine run against a plaintext
 * broker. Both are driven by the same YAML: the {@code APP_KAFKA_SECURITY_*}
 * / {@code APP_KAFKA_SASL_*} environment variables default to PLAINTEXT and
 * empty SASL settings, so an unset environment stays plaintext and only the
 * deploy opts in to SASL.
 */
class KafkaSecurityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_to_plaintext_with_no_sasl_when_env_is_unset() {
        runner.run(context -> {
            KafkaProperties props = context.getBean(KafkaProperties.class);
            assertThat(props.getSecurity().getProtocol()).isEqualTo("PLAINTEXT");
            assertThat(props.getProperties().get(SaslConfigs.SASL_MECHANISM)).isBlank();
            assertThat(props.getProperties().get(SaslConfigs.SASL_JAAS_CONFIG)).isBlank();
        });
    }

    @Test
    void binds_sasl_ssl_and_pem_truststore_from_app_kafka_environment_variables() {
        runner.withPropertyValues(
                        "APP_KAFKA_SECURITY_PROTOCOL=SASL_SSL",
                        "APP_KAFKA_SASL_MECHANISM=SCRAM-SHA-256",
                        "APP_KAFKA_SASL_JAAS_CONFIG="
                                + "org.apache.kafka.common.security.scram.ScramLoginModule "
                                + "required username=\"avnadmin\" password=\"secret\";",
                        "APP_KAFKA_SSL_TRUSTSTORE_TYPE=PEM",
                        "APP_KAFKA_SSL_TRUSTSTORE_CERTIFICATES="
                                + "-----BEGIN CERTIFICATE-----\nMIIE\n-----END CERTIFICATE-----")
                .run(context -> {
                    KafkaProperties props = context.getBean(KafkaProperties.class);
                    assertThat(props.getSecurity().getProtocol()).isEqualTo("SASL_SSL");
                    assertThat(props.getProperties())
                            .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256")
                            .containsEntry(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM")
                            .hasEntrySatisfying(
                                    SaslConfigs.SASL_JAAS_CONFIG,
                                    jaas -> assertThat(jaas).contains("ScramLoginModule"))
                            .hasEntrySatisfying(
                                    SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG,
                                    pem -> assertThat(pem).contains("BEGIN CERTIFICATE"));
                });
    }

    @EnableConfigurationProperties(KafkaProperties.class)
    static class TestConfig {
    }
}
