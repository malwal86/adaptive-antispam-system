package com.antispam.seed;

import java.util.Locale;

/**
 * The trusted class of a seed-corpus email, as given by the public source it came
 * from. Distinct from a model {@link com.antispam.decision.Decision}: a ground
 * truth is what the message actually <em>is</em>, used to train and evaluate, not
 * a verdict the system reached.
 */
public enum GroundTruthLabel {
    HAM,
    SPAM,
    PHISH;

    /**
     * Maps a corpus class-directory name to a label, absorbing the naming each
     * public source uses (SpamAssassin ships {@code easy_ham} / {@code hard_ham}).
     *
     * @throws IllegalArgumentException if the directory is not a recognized class
     */
    public static GroundTruthLabel fromDirectoryName(String directoryName) {
        return switch (directoryName.toLowerCase(Locale.ROOT)) {
            case "ham", "easy_ham", "hard_ham" -> HAM;
            case "spam" -> SPAM;
            case "phish", "phishing" -> PHISH;
            default -> throw new IllegalArgumentException(
                    "not a recognized ground-truth class directory: " + directoryName);
        };
    }

    /** The lowercase token stored in {@code ground_truth_labels.label}. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Inverse of {@link #dbValue()}, for reading a stored label back. */
    public static GroundTruthLabel fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
