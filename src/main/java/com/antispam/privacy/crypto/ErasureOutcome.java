package com.antispam.privacy.crypto;

/**
 * The result of an erasure (crypto-shred) request for one email.
 */
public enum ErasureOutcome {

    /** The key was destroyed by this request; the content is now unrecoverable. */
    ERASED,

    /** The key was already destroyed by an earlier request (idempotent erasure). */
    ALREADY_ERASED,

    /**
     * The email has no key store row, so there is nothing to crypto-shred — its body
     * was stored as plaintext (encryption was disabled when it was ingested). The
     * caller surfaces this honestly rather than pretending the content was erased.
     */
    NO_KEY,

    /** No email with this id exists. */
    NOT_FOUND
}
