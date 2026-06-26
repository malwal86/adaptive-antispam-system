package com.antispam.ingest.web;

import com.antispam.privacy.crypto.ErasureOutcome;
import java.util.UUID;

/**
 * Response for {@code POST /emails/{id}/erasure}: the email and the outcome of the
 * crypto-shred request (erased, already-erased, or no-key). The immutable row is
 * never touched; only the per-record data key is destroyed.
 *
 * @param emailId the email the erasure targeted
 * @param outcome what happened (see {@link ErasureOutcome})
 */
public record EmailErasureResponse(UUID emailId, ErasureOutcome outcome) {
}
