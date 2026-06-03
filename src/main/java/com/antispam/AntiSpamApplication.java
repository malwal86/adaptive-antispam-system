package com.antispam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Living Anti-Spam System — a single Spring Boot process
 * that every later slice (ingest, features, reputation, classifier, LLM
 * fallback, console) deploys into. This walking skeleton carries no business
 * logic: it boots, exposes health/info, and fails fast on misconfiguration.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AntiSpamApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntiSpamApplication.class, args);
    }
}
