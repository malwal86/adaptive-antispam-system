package com.antispam.reputation;

/**
 * The two kinds of reputation-affecting signal recorded in the append-only log
 * (story 03.01). Each event carries one of these; the Beta score sums their
 * weights into the corresponding bucket.
 *
 * <p>The enum name is the token stored verbatim in {@code reputation_events.signal}
 * and accepted on the API, so the on-the-wire, on-disk, and in-code vocabularies are
 * one and the same — there is no mapping table to drift.
 */
public enum ReputationSignal {

    /** A positive signal: ham delivered, or later positive engagement (Epic 07). */
    GOOD,

    /** A negative signal: a spam/abuse verdict, or later a negative feedback. */
    BAD
}
