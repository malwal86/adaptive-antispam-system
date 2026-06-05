package com.antispam;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Spring application context boots end-to-end against a real
 * Postgres (Flyway migrations apply, datasource connects) — the irreducible
 * "the process starts" guarantee that every later slice deploys into.
 */
class AntiSpamApplicationTests extends AbstractPostgresIntegrationTest {

    @Test
    void contextLoads() {
        // The assertion is the absence of a startup failure: if the context
        // cannot be built, @SpringBootTest fails this test.
    }
}
