package com.antispam.privacy.crypto;

import com.antispam.privacy.crypto.EmailContentKeyStore.StoredKey;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master-key rotation: re-wraps every live data key under the current active master
 * key, so that an old master key can be retired once every record points at the new
 * one. Only the small wrapped keys are touched — the immutable content ciphertext is
 * never rewritten — so rotation keeps all existing records decryptable throughout.
 *
 * <p>Erased records are skipped (their key is already gone), and records already
 * wrapped under the active version are left alone, making {@link #rotate} safe to run
 * repeatedly (idempotent once converged).
 */
@Service
public class KeyRotationService {

    private final EmailContentCipher cipher;
    private final EmailContentKeyStore keyStore;

    @Autowired
    public KeyRotationService(EmailContentCipher cipher, EmailContentKeyStore keyStore) {
        this.cipher = cipher;
        this.keyStore = keyStore;
    }

    /**
     * Re-wraps every data key not already under the active master key.
     *
     * @return the number of records re-wrapped
     */
    @Transactional
    public int rotate() {
        String active = cipher.activeKeyVersion();
        List<StoredKey> rewrappable = keyStore.findRewrappable();
        int rewrapped = 0;
        for (StoredKey key : rewrappable) {
            if (active.equals(key.masterKeyVersion())) {
                continue;
            }
            byte[] newWrap = cipher.rewrap(key.wrappedDek(), key.masterKeyVersion());
            keyStore.rewrap(key.emailId(), newWrap, active);
            rewrapped++;
        }
        return rewrapped;
    }
}
