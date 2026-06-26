package com.antispam.privacy.crypto;

import com.antispam.ingest.EmailRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Honors a right-to-erasure request (GDPR Art. 17) by crypto-shredding one email:
 * the per-record data key is destroyed in the mutable key store, which makes the
 * body ciphertext in the immutable {@code emails} row permanently unrecoverable —
 * without updating or deleting that row.
 *
 * <p>It exists to give the bare key-store erase a complete, honest outcome: it
 * distinguishes "no such email" from "email exists but was stored unencrypted, so
 * there is no key to destroy", which the raw {@link EmailContentKeyStore#erase}
 * cannot tell apart on its own.
 */
@Service
public class ErasureService {

    private final EmailRepository emails;
    private final EmailContentKeyStore keyStore;

    @Autowired
    public ErasureService(EmailRepository emails, EmailContentKeyStore keyStore) {
        this.emails = emails;
        this.keyStore = keyStore;
    }

    /**
     * Erases the body of {@code emailId} by destroying its data key.
     *
     * @return {@link ErasureOutcome#ERASED} / {@link ErasureOutcome#ALREADY_ERASED} on success,
     *         {@link ErasureOutcome#NOT_FOUND} if no such email exists, or
     *         {@link ErasureOutcome#NO_KEY} if the email exists but was stored unencrypted
     */
    public ErasureOutcome erase(UUID emailId) {
        ErasureOutcome outcome = keyStore.erase(emailId);
        if (outcome == ErasureOutcome.NO_KEY && !emails.existsById(emailId)) {
            return ErasureOutcome.NOT_FOUND;
        }
        return outcome;
    }
}
