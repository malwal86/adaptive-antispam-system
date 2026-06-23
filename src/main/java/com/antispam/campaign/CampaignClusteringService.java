package com.antispam.campaign;

import com.antispam.decision.embedding.EmailEmbeddingRepository;
import com.antispam.decision.embedding.EmbeddedEmail;
import com.antispam.decision.embedding.OnnxEmbeddingModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the offline embedding-clustering job and answers membership queries (story
 * 06.03). It is the seam between the stored embeddings (story 04.03) and the
 * {@code campaign_clusters} tables: load a model version's whole embedding corpus,
 * group it with the pure {@link CampaignClusterer}, and replace that version's
 * clusters in one transaction.
 *
 * <p><b>Offline by construction (AC 4).</b> Triggered out of band (a REST call or a
 * batch job), never on the synchronous decision path. It reads only
 * {@code email_embeddings} and writes only the {@code campaign_clusters} tables — it
 * never reads or mutates {@code classifications}, {@code reputation_events}, or any
 * in-flight decision, so clustering cannot change the verdict of an email being
 * decided. Re-running it (AC 5) recomputes the same partition deterministically and
 * rewrites the same rows, so it is safe to run repeatedly and stable enough to back
 * grouped eval splits (Epic 11).
 *
 * <p>The version clustered is the embedder's current
 * {@link OnnxEmbeddingModel#EMBEDDING_VERSION} — clusters are only meaningful within
 * one embedding space, mirroring how {@code EmailEmbeddingService} writes at one
 * version.
 */
@Service
public class CampaignClusteringService {

    private static final Logger log = LoggerFactory.getLogger(CampaignClusteringService.class);

    private final EmailEmbeddingRepository embeddings;
    private final CampaignClusterRepository clusters;
    private final CampaignClusterer clusterer;
    private final double similarityThreshold;

    @Autowired
    public CampaignClusteringService(EmailEmbeddingRepository embeddings,
            CampaignClusterRepository clusters, CampaignClusteringProperties properties) {
        this.embeddings = embeddings;
        this.clusters = clusters;
        this.similarityThreshold = properties.similarityThreshold();
        this.clusterer = new CampaignClusterer(properties.similarityThreshold());
    }

    /**
     * Clusters every embedding at the current version into campaigns and replaces the
     * stored clustering for that version. Returns a summary of the run.
     */
    @Transactional
    public ClusteringRun cluster() {
        String version = OnnxEmbeddingModel.EMBEDDING_VERSION;
        List<EmbeddedEmail> corpus = embeddings.findAll(version);
        List<EmailCluster> result = clusterer.cluster(corpus);
        clusters.replaceAll(version, result);
        log.info("campaign clustering: {} emails -> {} clusters at version={} (threshold={})",
                corpus.size(), result.size(), version, similarityThreshold);
        return new ClusteringRun(version, corpus.size(), result.size(), similarityThreshold);
    }

    /** The cluster an email belongs to at the current embedding version, if any. */
    @Transactional(readOnly = true)
    public Optional<ClusterMembership> membershipOf(UUID emailId) {
        return clusters.findMembership(emailId, OnnxEmbeddingModel.EMBEDDING_VERSION);
    }

    /**
     * Summary of one clustering run.
     *
     * @param modelVersion        the embedding version clustered
     * @param emailCount          embeddings fed into the run
     * @param clusterCount        campaigns produced
     * @param similarityThreshold the threshold the run used
     */
    public record ClusteringRun(
            String modelVersion, int emailCount, int clusterCount, double similarityThreshold) {
    }
}
