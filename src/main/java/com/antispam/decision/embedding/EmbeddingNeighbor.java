package com.antispam.decision.embedding;

import java.util.UUID;

/**
 * One result of a nearest-neighbor embedding query: an email and how similar its
 * stored embedding is to the query vector.
 *
 * @param emailId          the matched email
 * @param cosineSimilarity cosine similarity in {@code [-1, 1]}, 1.0 being identical
 *                         (exact, since stored embeddings are L2-normalized)
 */
public record EmbeddingNeighbor(UUID emailId, double cosineSimilarity) {
}
