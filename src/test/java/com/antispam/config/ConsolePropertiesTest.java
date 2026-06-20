package com.antispam.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The console CORS allow-list defaults to the local Next.js dev server when
 * unset, and otherwise takes exactly the configured origins (never a wildcard).
 */
class ConsolePropertiesTest {

    @Test
    void defaults_to_the_local_next_dev_server_when_unset() {
        assertThat(new ConsoleProperties(null).allowedOrigins())
                .containsExactly("http://localhost:3000");
        assertThat(new ConsoleProperties(List.of()).allowedOrigins())
                .containsExactly("http://localhost:3000");
    }

    @Test
    void takes_the_configured_origins_when_set() {
        ConsoleProperties properties =
                new ConsoleProperties(List.of("https://console.example", "https://staging.example"));

        assertThat(properties.allowedOrigins())
                .containsExactly("https://console.example", "https://staging.example");
    }
}
