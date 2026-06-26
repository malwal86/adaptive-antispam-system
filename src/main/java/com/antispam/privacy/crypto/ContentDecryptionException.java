package com.antispam.privacy.crypto;

/**
 * Thrown when an email body cannot be decrypted: the wrapped data key is missing
 * (crypto-shredded), the master key version is unknown, or the ciphertext/key
 * failed its authentication tag (tampering or a wrong key). It deliberately
 * carries no key material or plaintext — only that decryption failed.
 *
 * <p>For a crypto-shredded record this is the <i>expected</i> outcome, not a
 * fault: the data key was destroyed on purpose to honor an erasure request, so
 * callers translate it into an "content erased" state rather than an error.
 */
public class ContentDecryptionException extends RuntimeException {

    public ContentDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ContentDecryptionException(String message) {
        super(message);
    }
}
