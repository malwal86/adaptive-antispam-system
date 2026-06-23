package com.antispam.campaign;

import java.util.UUID;

/**
 * One email's membership in a campaign cluster: which email, and how close its
 * embedding is to the cluster's final centroid. The similarity is recomputed
 * against the finished centroid (not the running one seen at assignment time), so
 * it reflects how central the member is to the campaign as a whole.
 *
 * @param emailId          the member email
 * @param cosineSimilarity cosine similarity of the email's embedding to the cluster
 *                         centroid, in {@code [-1, 1]}
 */
public record ClusterMember(UUID emailId, double cosineSimilarity) {

    public ClusterMember {
        if (emailId == null) {
            throw new IllegalArgumentException("emailId is required");
        }
    }
}
