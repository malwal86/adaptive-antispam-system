package com.antispam.campaign;

import java.util.UUID;

/**
 * The campaign an email belongs to, as queried per email (story 06.03 AC 3): the
 * cluster's id and size, the embedding version it was clustered under, and how close
 * the email sits to the centroid. This is the join of {@code campaign_cluster_members}
 * and {@code campaign_clusters} the API returns and the eval split (Epic 11) reads as
 * a group label.
 *
 * @param emailId          the queried email
 * @param clusterId        the campaign cluster it belongs to
 * @param modelVersion     the embedding version the clustering ran under
 * @param cosineSimilarity the email's similarity to its cluster centroid, in {@code [-1, 1]}
 * @param clusterSize      how many emails are in the cluster
 */
public record ClusterMembership(
        UUID emailId, UUID clusterId, String modelVersion, double cosineSimilarity, int clusterSize) {
}
