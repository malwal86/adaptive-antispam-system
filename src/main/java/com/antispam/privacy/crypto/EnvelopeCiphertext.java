package com.antispam.privacy.crypto;

/**
 * The product of encrypting one email body for storage at rest: the content
 * ciphertext, the per-record data key wrapped under a master key, and the
 * version of the master key that did the wrapping.
 *
 * <p>The {@code ciphertext} is what replaces the raw bytes in the immutable
 * {@code emails.raw_content} column; the {@code wrappedDek} and
 * {@code masterKeyVersion} are persisted separately in the mutable key store
 * ({@code email_content_keys}). Splitting them is what makes crypto-shredding
 * possible: the immutable ciphertext can never be rewritten, but destroying the
 * wrapped data key in the key store leaves it permanently unrecoverable.
 *
 * @param ciphertext       IV-prefixed AES-GCM ciphertext of the email body
 * @param wrappedDek       the data key, itself AES-GCM-encrypted under the master key
 * @param masterKeyVersion identifier of the master key used to wrap {@code wrappedDek}
 */
public record EnvelopeCiphertext(byte[] ciphertext, byte[] wrappedDek, String masterKeyVersion) {
}
