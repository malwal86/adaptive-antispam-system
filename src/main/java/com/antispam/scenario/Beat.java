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
 */
public enum Beat {
    WARMUP,
    ATTACK,
    SPOOF,
    MISCONFIGURED_LEGIT;

    /**
     * The ingest {@code source} provenance recorded for an email of this beat, e.g. {@code
     * "thunderclap-warmup"} — distinct per beat so the scenario's emails are auditable apart from
     * ordinary traffic and apart from each other.
     */
    public String source() {
        return "thunderclap-" + name().toLowerCase().replace('_', '-');
    }
}
