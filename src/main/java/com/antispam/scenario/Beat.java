package com.antispam.scenario;

/**
 * The narrative role a scenario email plays in the thunderclap demo (story 12.05). Each beat maps to
 * one of the headline moments the console's right panel and centre stream are built to show, so the
 * scenario runner can tag every email it injects with the story moment it advances:
 *
 * <ul>
 *   <li>{@link #WARMUP} — authenticated, benign mail from the hero sender; its reputation <em>rises</em>.</li>
 *   <li>{@link #ATTACK} — a mutated, near-duplicate phishing campaign from that same warmed sender; the
 *       reputation curve <em>collapses</em>, burst detection fires, routing escalates to the LLM, and the
 *       cost meter ticks.</li>
 *   <li>{@link #SPOOF} — an <em>unauthenticated</em> impersonation of the warmed domain; auth gating
 *       (story 03.03) means it inherits none of the earned trust — it gets nothing.</li>
 *   <li>{@link #MISCONFIGURED_LEGIT} — a different, genuinely legitimate sender whose auth is broken;
 *       the soft gate still lets its good content slowly earn trust.</li>
 * </ul>
 *
 * <p>The beat is provenance, not control flow: every email travels the same live pipeline. The tag is
 * what lets a test (and a human watching the demo) say which moment a given decision belongs to.
 *
 * <p>The last three roles — {@link #LEGIT}, {@link #NEWSLETTER}, {@link #SPAM} — belong to the
 * calmer "a normal morning" scenario (routine inbox triage), not the thunderclap; they read the same
 * way in any scenario. A scenario stamps each email's ingest source explicitly (see
 * {@link ScenarioEmail}), so a beat's own {@link #source()} is only the thunderclap default.
 */
public enum Beat {
    WARMUP,
    ATTACK,
    SPOOF,
    MISCONFIGURED_LEGIT,

    /** A genuinely legitimate, authenticated personal or transactional email — plainly benign. */
    LEGIT,
    /** Authenticated bulk mail (a newsletter/marketing blast) — benign volume, not abuse. */
    NEWSLETTER,
    /** Obvious junk from an unknown, unauthenticated sender — the clear-cut block. */
    SPAM;

    /**
     * The thunderclap scenario's default ingest {@code source} for an email of this beat, e.g.
     * {@code "thunderclap-warmup"}. Other scenarios pass their own source to {@link ScenarioEmail}
     * rather than calling this, so their mail is auditable apart from the thunderclap and each other.
     */
    public String source() {
        return "thunderclap-" + name().toLowerCase().replace('_', '-');
    }
}
