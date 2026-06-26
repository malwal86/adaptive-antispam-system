package com.antispam.privacy;

import com.antispam.event.SenderKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a sender identity into a stable, keyed pseudonym for de-identified exports
 * (story 14.04). It is a keyed HMAC-SHA-256: the same sender always maps to the same
 * pseudonym (so grouped and time-forward splits, and reputation lineage, keep working
 * — Epic 11), different senders map to different pseudonyms, and without the secret
 * key the real identity cannot be derived from the pseudonym.
 *
 * <p>HMAC, not a plain hash, precisely because a plain SHA-256 of an email address is
 * trivially reversible by a dictionary of addresses; the secret key defeats that. The
 * key is sourced from the environment/secret manager, never the repo.
 */
public final class SenderPseudonymizer {

    /** A short, recognizable tag so a pseudonym reads as one in an export. */
    private static final String PREFIX = "snd_";

    /** Truncate the 32-byte HMAC to 12 bytes (24 hex chars): compact, collision-safe for grouping. */
    private static final int PSEUDONYM_BYTES = 12;

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SenderPseudonymizer(byte[] hmacKey) {
        this.key = new SecretKeySpec(hmacKey, ALGORITHM);
    }

    /**
     * The stable pseudonym for a sender identity. Null/blank collapses to the shared
     * {@link SenderKey#UNKNOWN} bucket (so unknown senders group together, as they do
     * everywhere else in the system).
     */
    public String pseudonym(String senderKey) {
        String input = senderKey == null || senderKey.isBlank() ? SenderKey.UNKNOWN : senderKey;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest, 0, PSEUDONYM_BYTES);
        } catch (java.security.NoSuchAlgorithmException e) {
            // HmacSHA256 is mandated on every JVM.
            throw new IllegalStateException("HmacSHA256 not available", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("invalid HMAC key for sender pseudonymization", e);
        }
    }
}
