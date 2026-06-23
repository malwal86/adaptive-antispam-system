package com.antispam.campaign;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The offline clusterer's one knob, bound from {@code antispam.campaign-clustering}
 * (story 06.03): the cosine similarity at or above which two emails are the same
 * campaign. It lives in config, not code, so the "how reworded is still one
 * campaign" line can be tuned without a redeploy.
 *
 * <p>The default {@code 0.80} sits in the gap the embedding benchmark measured: a
 * reworded same-topic pair scores ~0.85–0.94, an unrelated pair well under 0.5, so
 * 0.80 co-clusters reworded variants while keeping distinct campaigns apart.
 *
 * @param similarityThreshold minimum cosine similarity to join a cluster, in {@code (0, 1]}
 */
@Validated
@ConfigurationProperties(prefix = "antispam.campaign-clustering")
public record CampaignClusteringProperties(double similarityThreshold) {

    /** Surfaces the same {@code (0, 1]} contract {@link CampaignClusterer} enforces, at startup. */
    public CampaignClusteringProperties {
        if (!(similarityThreshold > 0.0 && similarityThreshold <= 1.0)) {
            throw new IllegalArgumentException(
                    "antispam.campaign-clustering.similarity-threshold must be in (0, 1], was: "
                            + similarityThreshold);
        }
    }
}
