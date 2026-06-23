package com.antispam.decision.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The quarantine-pending resolution's SLA (story 05.06), bound from {@code antispam.llm.quarantine}.
 * The SLA is the bounded deadline within which the async LLM resolution must promote-to-inbox or
 * confirm-spam; past it, the decision fail-degrades (PRD §Live decision timing: "~30s SLA, else
 * fail-degrade"). It lives in config so the deadline is tunable per environment without a redeploy.
 *
 * @param sla the maximum time the async resolution may take before it fail-degrades (default 30s)
 */
@Validated
@ConfigurationProperties(prefix = "antispam.llm.quarantine")
public record QuarantinePendingProperties(Duration sla) {

    public QuarantinePendingProperties {
        if (sla == null) {
            sla = Duration.ofSeconds(30);
        }
        if (sla.isNegative() || sla.isZero()) {
            throw new IllegalArgumentException("antispam.llm.quarantine.sla must be positive");
        }
    }
}
