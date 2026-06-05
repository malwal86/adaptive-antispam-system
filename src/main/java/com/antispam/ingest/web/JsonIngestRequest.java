package com.antispam.ingest.web;

/**
 * JSON ingest body — structured form of {@code POST /emails}. The {@code raw}
 * field carries the full RFC-822 message (headers + body); {@code source} is
 * optional provenance.
 */
public record JsonIngestRequest(String raw, String source) {
}
