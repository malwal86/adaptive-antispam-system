package com.antispam.analyze;

import java.util.UUID;

/**
 * JSON body for {@code POST /analyze}. Exactly one of {@code raw} or
 * {@code emailId} is expected:
 *
 * <ul>
 *   <li>{@code raw} — a pasted RFC-822 message to ingest and decide (the
 *       paste-and-submit path).</li>
 *   <li>{@code emailId} — an already-ingested email (e.g. a labeled seed sample
 *       chosen in the picker) to decide without re-pasting; no PII crosses the
 *       wire.</li>
 * </ul>
 *
 * {@code source} is optional ingest provenance for the {@code raw} path.
 *
 * @param raw     the raw email to analyse, or null when analysing by id
 * @param source  optional ingest provenance for {@code raw}
 * @param emailId an existing email to analyse, or null when analysing pasted raw
 */
public record AnalyzeRequest(String raw, String source, UUID emailId) {
}
