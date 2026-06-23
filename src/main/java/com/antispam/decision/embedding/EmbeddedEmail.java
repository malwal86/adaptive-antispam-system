package com.antispam.decision.embedding;

import java.util.UUID;

/**
 * One email's stored embedding: an id and its L2-normalized vector. This is how the
 * {@code email_embeddings} table reads back in bulk ({@link EmailEmbeddingRepository#findAll})
 * and the input the offline campaign clusterer (story 06.03) groups in memory — so
 * clustering depends on the embedding layer, never the reverse, and never touches
 * the live decision path.
 *
 * @param emailId   the email this vector describes
 * @param embedding the email's embedding (expected unit-length, per OnnxEmbeddingModel)
 */
public record EmbeddedEmail(UUID emailId, float[] embedding) {

    public EmbeddedEmail {
        if (emailId == null) {
            throw new IllegalArgumentException("emailId is required");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding is required and non-empty");
        }
    }
}
