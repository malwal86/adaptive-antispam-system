package com.antispam.scenario;

/**
 * One scripted email in a scenario: the raw RFC-822 bytes to inject and the {@link Beat} they
 * advance. The bytes are the real, parseable message the live pipeline ingests, parses, and decides —
 * there is no out-of-band signalling, so every demo visual the email produces is a genuine backend
 * signal, not a scripted animation.
 *
 * @param beat   the story moment this email advances; also names its ingest {@code source}
 * @param raw    the raw RFC-822 message bytes, ingested verbatim
 */
public record ScenarioEmail(Beat beat, byte[] raw) {

    public ScenarioEmail {
        if (beat == null) {
            throw new IllegalArgumentException("beat must not be null");
        }
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("raw must not be empty");
        }
    }

    /** The ingest provenance to tag this email with — {@link Beat#source()}. */
    public String source() {
        return beat.source();
    }
}
