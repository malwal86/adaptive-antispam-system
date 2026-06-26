package com.antispam.privacy.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption for email bodies at rest, with the master keys held in
 * memory (sourced from the environment/secret manager, never the repo).
 *
 * <p><b>Scheme.</b> Each body is encrypted under a fresh 256-bit data key (DEK)
 * with AES-GCM; that DEK is then itself encrypted ("wrapped") under a master key,
 * also with AES-GCM. Only the wrapped DEK is persisted, alongside the version of
 * the master key that wrapped it. This is the standard envelope pattern: bulk
 * data is symmetric-encrypted under a cheap per-record key, and only the small
 * key is ever re-encrypted (e.g. on master-key rotation, via {@link #rewrap}).
 *
 * <p><b>Why it enables crypto-shredding.</b> The content ciphertext lives in the
 * immutable {@code emails} row and is never rewritten; the wrapped DEK lives in a
 * separate, mutable key store. Destroying the wrapped DEK there makes the
 * ciphertext permanently unrecoverable — erasure (GDPR Art. 17) without mutating
 * the immutable record (ISO/IEC 20889 de-identification by key destruction).
 *
 * <p><b>Rotation.</b> Multiple master-key versions are held at once: the active
 * version wraps new DEKs, and any older version is retained so existing records
 * stay decryptable until {@link #rewrap} re-wraps their DEK under the active key.
 *
 * <p>Instances are immutable and thread-safe: each call allocates its own
 * {@link Cipher} (which is not thread-safe) and a per-call IV.
 */
public final class EmailContentCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int DEK_BYTES = 32;

    private final Map<String, SecretKeySpec> masterKeys;
    private final String activeKeyVersion;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param masterKeysByVersion master key bytes (each 16/24/32 bytes for AES) keyed by version
     * @param activeKeyVersion    the version used to wrap newly generated data keys; may be null
     *                            only when {@code masterKeysByVersion} is empty (cipher disabled)
     */
    public EmailContentCipher(Map<String, byte[]> masterKeysByVersion, String activeKeyVersion) {
        this.masterKeys = masterKeysByVersion.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> new SecretKeySpec(e.getValue(), KEY_ALGORITHM)));
        if (!this.masterKeys.isEmpty() && !this.masterKeys.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException(
                    "active master key version '" + activeKeyVersion + "' has no configured key");
        }
        this.activeKeyVersion = activeKeyVersion;
    }

    /** Whether any master key is configured. When false, nothing is encrypted at rest. */
    public boolean isEnabled() {
        return !masterKeys.isEmpty();
    }

    /** The master key version newly encrypted records are wrapped under. */
    public String activeKeyVersion() {
        return activeKeyVersion;
    }

    /**
     * Encrypts {@code plaintext} under a fresh data key wrapped by the active master
     * key.
     *
     * @throws IllegalStateException if the cipher is disabled (no master key configured)
     */
    public EnvelopeCiphertext encrypt(byte[] plaintext) {
        if (!isEnabled()) {
            throw new IllegalStateException("encryption is not configured");
        }
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        try {
            byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, KEY_ALGORITHM), plaintext);
            byte[] wrappedDek = gcm(Cipher.ENCRYPT_MODE, masterKeys.get(activeKeyVersion), dek);
            return new EnvelopeCiphertext(ciphertext, wrappedDek, activeKeyVersion);
        } catch (GeneralSecurityException e) {
            // The key sizes and transform are fixed and valid, so a failure here is a
            // programming/JVM fault, not a recoverable condition — crash early.
            throw new IllegalStateException("failed to encrypt email content", e);
        }
    }

    /**
     * Decrypts a stored body.
     *
     * @param ciphertext       the IV-prefixed content ciphertext
     * @param wrappedDek       the wrapped data key from the key store ({@code byte[0]} once erased)
     * @param masterKeyVersion the master key version recorded with the wrapped DEK
     * @throws ContentDecryptionException if the DEK was destroyed, the version is unknown, or the
     *                                    ciphertext/key fails authentication (tampering / wrong key)
     */
    public byte[] decrypt(byte[] ciphertext, byte[] wrappedDek, String masterKeyVersion) {
        SecretKeySpec masterKey = masterKeys.get(masterKeyVersion);
        if (masterKey == null) {
            throw new ContentDecryptionException("unknown master key version: " + masterKeyVersion);
        }
        try {
            byte[] dek = gcm(Cipher.DECRYPT_MODE, masterKey, wrappedDek);
            return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, KEY_ALGORITHM), ciphertext);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ContentDecryptionException("email content could not be decrypted", e);
        }
    }

    /**
     * Re-wraps a data key under the active master key, given the version it was last
     * wrapped under. The content ciphertext is never touched — only the small wrapped
     * key changes — so rotation keeps existing immutable records readable.
     *
     * @return the data key re-wrapped under {@link #activeKeyVersion()}
     * @throws ContentDecryptionException if the current wrap can't be opened (unknown version / tamper)
     */
    public byte[] rewrap(byte[] wrappedDek, String fromVersion) {
        SecretKeySpec from = masterKeys.get(fromVersion);
        if (from == null) {
            throw new ContentDecryptionException("unknown master key version: " + fromVersion);
        }
        try {
            byte[] dek = gcm(Cipher.DECRYPT_MODE, from, wrappedDek);
            return gcm(Cipher.ENCRYPT_MODE, masterKeys.get(activeKeyVersion), dek);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ContentDecryptionException("wrapped data key could not be re-wrapped", e);
        }
    }

    /**
     * AES-GCM in one shot. On encrypt, a fresh random IV is generated and prefixed to
     * the output; on decrypt, that prefix is read back as the IV. Prefixing the IV
     * keeps the stored form self-contained (no separate IV column to manage).
     */
    private byte[] gcm(int mode, SecretKeySpec key, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        if (mode == Cipher.ENCRYPT_MODE) {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] body = cipher.doFinal(input);
            byte[] out = new byte[GCM_IV_BYTES + body.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
            System.arraycopy(body, 0, out, GCM_IV_BYTES, body.length);
            return out;
        }
        if (input.length < GCM_IV_BYTES) {
            throw new IllegalArgumentException("ciphertext too short to contain an IV");
        }
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, input, 0, GCM_IV_BYTES));
        return cipher.doFinal(input, GCM_IV_BYTES, input.length - GCM_IV_BYTES);
    }
}
