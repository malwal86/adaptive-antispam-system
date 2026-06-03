package com.antispam;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the Spring application context boots end-to-end with valid service
 * configuration present — the irreducible "the process starts" guarantee that
 * every later slice deploys into.
 */
@SpringBootTest
@ActiveProfiles("test")
class AntiSpamApplicationTests {

    @Test
    void contextLoads() {
        // The assertion is the absence of a startup failure: if the context
        // cannot be built, @SpringBootTest fails this test.
    }
}
