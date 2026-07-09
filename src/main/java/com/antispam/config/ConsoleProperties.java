package com.antispam.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console-facing web config, bound from {@code antispam.console}. The Living Spam Classifier Lab
 * Console (and the 01.05 analyzer) is a separate Next.js service that calls this
 * API cross-origin, so its origin(s) must be allowed for CORS.
 *
 * <p>Defaults to the local Next.js dev server ({@code http://localhost:3000}).
 * In a real deployment, set the hosted console origin(s) via
 * {@code ANTISPAM_CONSOLE_ALLOWED_ORIGINS} (comma-separated). Entries are treated
 * as CORS origin <em>patterns</em>, so a scoped wildcard such as
 * {@code https://*.vercel.app} (the console's per-deploy preview URLs) is allowed
 * alongside the production origin — but never an unrestricted {@code *}.
 *
 * @param allowedOrigins browser origins permitted to call the API cross-origin
 */
@ConfigurationProperties(prefix = "antispam.console")
public record ConsoleProperties(List<String> allowedOrigins) {

    public ConsoleProperties {
        allowedOrigins = (allowedOrigins == null || allowedOrigins.isEmpty())
                ? List.of("http://localhost:3000")
                : List.copyOf(allowedOrigins);
    }
}
