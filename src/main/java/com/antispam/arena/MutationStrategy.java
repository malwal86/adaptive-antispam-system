package com.antispam.arena;

import java.util.Locale;

/**
 * The seed-grounded perturbation a red-team attack applies to a real spam, logged per variant (PRD
 * §Subsystem 6, story 08.01). The four categories are the ones the PRD enumerates; each is a
 * <em>different way to evade the filter without changing the message's malicious intent</em>, which
 * is why mutating a spam leaves it a spam (the ground-truth label is preserved by construction).
 *
 * <p>The strategy is part of the lineage record, so it is stored as a stable lowercase token (like
 * {@link com.antispam.seed.GroundTruthLabel}) rather than an ordinal. Each value also carries the
 * attacker instruction that tells the configured attacker model which perturbation to make — the
 * engine logs only the category, never re-deriving it from the prompt.
 */
public enum MutationStrategy {

    /** Swap words for synonyms / paraphrase tokens, preserving meaning but changing surface n-grams. */
    SYNONYM("Rewrite the message by replacing words and phrases with synonyms or close paraphrases. "
            + "Preserve the meaning and the call to action exactly; change only the wording."),

    /** Substitute select Latin letters with visually similar Unicode homoglyphs to dodge token matching. */
    HOMOGLYPH("Substitute a few Latin letters in salient words with visually similar Unicode homoglyphs "
            + "(for example Cyrillic or Greek look-alikes). Keep the text visually readable; change only glyphs."),

    /** Restructure layout, formatting, ordering, or whitespace without changing the content. */
    STRUCTURE("Restructure the message's layout, ordering, whitespace, and formatting (line breaks, "
            + "punctuation, simple HTML) without changing any of the words or the intent."),

    /** Reframe the pitch with a fresh pretext while keeping the same ask/payload. */
    REFRAME("Reframe the message with a different pretext or narrative while keeping the same underlying "
            + "request, link, or payload. The cover story changes; the malicious ask does not.");

    private final String attackerInstruction;

    MutationStrategy(String attackerInstruction) {
        this.attackerInstruction = attackerInstruction;
    }

    /** The strategy-specific guidance appended to the attacker's system prompt. */
    public String attackerInstruction() {
        return attackerInstruction;
    }

    /** The lowercase token stored in {@code adversarial_emails.mutation_strategy}. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Inverse of {@link #dbValue()}, for reading a stored strategy back.
     *
     * @throws IllegalArgumentException if {@code value} is not a recognized strategy token
     */
    public static MutationStrategy fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
