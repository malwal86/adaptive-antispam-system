package com.antispam;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for full-context tests that exercise the real persistence layer. A single
 * Postgres container is shared across the whole test suite; {@code @ServiceConnection}
 * wires Spring's datasource to it and Flyway runs the migrations on startup, so
 * the immutability trigger and idempotency behavior are tested against the actual
 * database — not a stand-in.
 *
 * <p><b>Singleton container, not {@code @Container}:</b> the container is started
 * once for the JVM in the static initializer and never explicitly stopped (Ryuk
 * reaps it at exit). {@code @Container} would stop the container after each test
 * class, but Spring caches the application context <i>across</i> classes — a later
 * class would then reuse a cached context still pointing at the stopped
 * container's port and fail with "connection refused". Sharing one long-lived
 * container keeps every cached context valid.
 *
 * <p>{@code disabledWithoutDocker = true} makes these tests skip (not fail) on
 * machines without a running Docker daemon; CI runs them in full. The start is
 * guarded by the same Docker check so this initializer doesn't throw before the
 * skip condition is evaluated.
 *
 * <p><b>pgvector image.</b> The image is {@code pgvector/pgvector:pg16} (Postgres
 * 16 plus the pgvector extension) rather than the stock {@code postgres:16-alpine},
 * because story 04.03's {@code V13} migration does {@code create extension vector}
 * and every cached context runs the full migration set on startup. It is a drop-in
 * Postgres 16 for every other test; {@code asCompatibleSubstituteFor} tells
 * Testcontainers to treat it as the postgres module's image.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }
}
