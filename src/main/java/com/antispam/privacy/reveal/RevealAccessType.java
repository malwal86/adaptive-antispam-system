package com.antispam.privacy.reveal;

/**
 * The kind of privileged, redaction-bypassing access recorded in the audit (story
 * 14.05). The {@link #dbValue} matches the {@code reveal_access_audit.access_type}
 * check constraint.
 */
public enum RevealAccessType {

    /** An unredacted JSON view via {@code ?reveal=true}. */
    REVEAL("reveal"),

    /** The raw message bytes via {@code /emails/{id}/raw}. */
    RAW("raw"),

    /** A crypto-shred erasure of the body. */
    ERASURE("erasure");

    private final String dbValue;

    RevealAccessType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
