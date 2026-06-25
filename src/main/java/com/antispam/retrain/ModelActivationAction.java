package com.antispam.retrain;

/**
 * The two activation changes the retrain loop records in the audit log (story 10.04): promoting a
 * gated candidate live, or rolling back to a prior policy. A typed enum rather than a bare string so a
 * caller cannot record an action the {@code model_activation_audit} check constraint would reject — the
 * {@link #dbValue()} is the exact token the column allows.
 */
public enum ModelActivationAction {
    PROMOTE("promote"),
    ROLLBACK("rollback");

    private final String dbValue;

    ModelActivationAction(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The token stored in {@code model_activation_audit.action}. */
    public String dbValue() {
        return dbValue;
    }

    /** Resolves the action from its stored token. */
    public static ModelActivationAction fromDbValue(String dbValue) {
        for (ModelActivationAction action : values()) {
            if (action.dbValue.equals(dbValue)) {
                return action;
            }
        }
        throw new IllegalArgumentException("unknown activation action: " + dbValue);
    }
}
