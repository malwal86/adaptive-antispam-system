package com.antispam.eval;

import java.util.Locale;

/**
 * Which side of a train/eval split an email lands on. The eval side is the
 * held-out set every honest measurement draws on — calibration's reliability set
 * (story 04.02), the promotion gate's golden judging set (Epic 10/11) — so an
 * email must never appear on both sides, and no near-duplicate family may straddle
 * the boundary (story 11.01).
 */
public enum SplitSide {
    TRAIN,
    EVAL;

    /** The lowercase token stored in {@code eval_split_assignments.split_side}. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Inverse of {@link #dbValue()}, for reading a stored side back. */
    public static SplitSide fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
