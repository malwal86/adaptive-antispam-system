package com.antispam.campaign.web;

import com.antispam.campaign.ClusterMembership;
import java.util.UUID;

/**
 * {@code GET /campaigns/clusters/email/{emailId}} result: which campaign an email
 * belongs to and how central it is to it. {@code clusterSize} lets a caller tell a
 * lone email from a member of a large coordinated blast at a glance.
 *
 * @param emailId          the queried email
 * @param clusterId        the campaign cluster it belongs to
 * @param modelVersion     the embedding version the clustering ran under
 * @param cosineSimilarity similarity to the cluster centroid, in {@code [-1, 1]}
 * @param clusterSize      how many emails are in the cluster
 */
public record ClusterMembershipResponse(
        UUID emailId, UUID clusterId, String modelVersion, double cosineSimilarity, int clusterSize) {

    public static ClusterMembershipResponse from(ClusterMembership membership) {
        return new ClusterMembershipResponse(
                membership.emailId(),
                membership.clusterId(),
                membership.modelVersion(),
                membership.cosineSimilarity(),
                membership.clusterSize());
    }
}
