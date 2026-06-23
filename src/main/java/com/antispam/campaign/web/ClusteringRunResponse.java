package com.antispam.campaign.web;

import com.antispam.campaign.CampaignClusteringService.ClusteringRun;

/**
 * {@code POST /campaigns/cluster} result: what the offline run just did. The counts
 * are the headline evidence — how many embeddings were grouped into how many
 * campaigns at what threshold and embedding version.
 *
 * @param modelVersion        the embedding version clustered
 * @param emailCount          embeddings fed into the run
 * @param clusterCount        campaigns produced
 * @param similarityThreshold the cosine threshold the run used
 */
public record ClusteringRunResponse(
        String modelVersion, int emailCount, int clusterCount, double similarityThreshold) {

    public static ClusteringRunResponse from(ClusteringRun run) {
        return new ClusteringRunResponse(
                run.modelVersion(), run.emailCount(), run.clusterCount(), run.similarityThreshold());
    }
}
