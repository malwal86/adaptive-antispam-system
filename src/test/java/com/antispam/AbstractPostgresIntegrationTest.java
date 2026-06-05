package com.antispam;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for full-context tests that exercise the real persistence layer. A single
 * Postgres container is shared across the suite; {@code @ServiceConnection} wires
 * Spring's datasource to it and Flyway runs the migrations on startup, so the
 * immutability trigger and idempotency behavior are tested against the actual
 * database — not a stand-in.
 *
 * <p>{@code disabledWithoutDocker = true} makes these tests skip (not fail) on
 * machines without a running Docker daemon; CI runs them in full.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
