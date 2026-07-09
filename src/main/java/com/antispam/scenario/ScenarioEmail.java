package com.antispam.scenario;

/**
 * One scripted email in a scenario: the raw RFC-822 bytes to inject, the {@link Beat} they advance,
 * and the ingest {@code source} provenance to tag them with. The bytes are the real, parseable
 * message the live pipeline ingests, parses, and decides — there is no out-of-band signalling, so
 * every demo visual the email produces is a genuine backend signal, not a scripted animation.
 *
 * <p>The {@code source} is explicit so each scenario can stamp its own provenance (e.g.
 * {@code "thunderclap-warmup"}, {@code "normal-morning-spam"}) rather than sharing one namespace —
 * that is what lets a test, and an audit, tell one scenario's injected mail from another's and from
 * ordinary traffic. The two-arg convenience constructor defaults the source to the beat's own
 * {@link Beat#source()}, which is what the original thunderclap scenario uses.
 *
 * @param beat   the story moment this email advances
 * @param source the ingest provenance this email is tagged with; must not be blank
 * @param raw    the raw RFC-822 message bytes, ingested verbatim
 */
public record ScenarioEmail(Beat beat, String source, byte[] raw) {

    public ScenarioEmail {
        if (beat == null) {
            throw new IllegalArgumentException("beat must not be null");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("raw must not be empty");
        }
    }

    /** Builds an email tagged with its {@link Beat#source() beat's default source}. */
    public ScenarioEmail(Beat beat, byte[] raw) {
        this(beat, beat == null ? null : beat.source(), raw);
    }
}
