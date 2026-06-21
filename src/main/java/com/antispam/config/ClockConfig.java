package com.antispam.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the system {@link Clock} the application reads "now" from. Injecting a
 * clock rather than calling {@code Instant.now()} directly is what lets time-dependent
 * logic — read-time reputation decay (story 03.02) is the first — be driven by a
 * synthetic timeline in tests without sleeping, while production runs on UTC wall
 * time. UTC (not the default zone) keeps timestamps consistent across hosts.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
