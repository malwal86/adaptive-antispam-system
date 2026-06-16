package com.antispam.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the separate Next.js console to call this API from the browser. CORS is
 * scoped to the configured console origin(s) (see {@link ConsoleProperties}) and
 * to the read/decide verbs the console uses — never a wildcard origin.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final ConsoleProperties console;

    @Autowired
    public WebCorsConfig(ConsoleProperties console) {
        this.console = console;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // allowedOriginPatterns (not allowedOrigins) so a wildcard like
        // https://*.vercel.app matches the console's per-deploy preview URLs as
        // well as its production origin; exact origins still match literally.
        registry.addMapping("/**")
                .allowedOriginPatterns(console.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
