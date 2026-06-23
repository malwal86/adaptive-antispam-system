package com.antispam.campaign;

import com.antispam.decision.embedding.EmbeddedEmail;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Groups email embeddings into coordinated-campaign clusters by semantic
 * similarity — the offline, expensive tier of campaign detection (story 06.03).
 * Where SimHash/MinHash (06.02) catch templated blasts by surface form, this works
 * in embedding space, so it catches <em>reworded</em> variants that share meaning
 * but not bytes — exactly the mutations the arena (Epic 08) produces.
 *
 * <p><b>Algorithm — single-pass greedy threshold clustering (leader clustering).</b>
 * Emails are visited in a fixed order (ascending {@code emailId}); each email joins
 * the existing cluster whose centroid it is most similar to, if that similarity is
 * at least {@code similarityThreshold}, otherwise it seeds a new cluster. A
 * cluster's centroid is the running normalized mean of its members, so it tracks
 * the campaign as members accrue. This is intentionally simple rather than k-means:
 * it needs no pre-chosen k (the number of campaigns is unknown), it is a single
 * deterministic pass, and the only knob — the threshold — maps directly onto the
 * "how reworded is still the same campaign" question the test set measures.
 *
 * <p><b>Determinism (AC 5).</b> The fixed visitation order makes the partition a
 * pure function of the input set, independent of the order embeddings were loaded.
 * Two runs over the same emails therefore produce the same clusters, and (via
 * {@link EmailCluster#deterministicId()}) the same cluster ids — the stability the
 * grouped eval split (Epic 11) depends on.
 *
 * <p>Pure and stateless: no Spring, no I/O, no live-state access. It cannot run on
 * the fast path or mutate a decision (AC 4) — it only turns a list of vectors into
 * a list of clusters.
 */
public class CampaignClusterer {

    private final double similarityThreshold;

    /**
     * @param similarityThreshold the minimum cosine similarity, in {@code (0, 1]},
     *     for an email to join an existing cluster rather than start a new one;
     *     higher demands a tighter campaign
     */
    public CampaignClusterer(double similarityThreshold) {
        if (!(similarityThreshold > 0.0 && similarityThreshold <= 1.0)) {
            throw new IllegalArgumentException(
                    "similarityThreshold must be in (0, 1], was: " + similarityThreshold);
        }
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Partitions {@code emails} into campaign clusters. An empty input yields no
     * clusters; every email lands in exactly one cluster of the result.
     */
    public List<EmailCluster> cluster(List<EmbeddedEmail> emails) {
        List<EmbeddedEmail> ordered = emails.stream()
                .sorted(Comparator.comparing(EmbeddedEmail::emailId))
                .toList();

        List<Accumulator> clusters = new ArrayList<>();
        for (EmbeddedEmail email : ordered) {
            // Compare in unit space so cosine is well-defined even if a stored
            // vector drifted slightly off unit length.
            float[] vector = VectorMath.normalize(email.embedding());

            Accumulator best = null;
            double bestSimilarity = Double.NEGATIVE_INFINITY;
            for (Accumulator cluster : clusters) {
                double similarity = VectorMath.cosine(vector, cluster.centroid());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = cluster;
                }
            }

            if (best != null && bestSimilarity >= similarityThreshold) {
                best.add(email.emailId(), vector);
            } else {
                Accumulator seed = new Accumulator();
                seed.add(email.emailId(), vector);
                clusters.add(seed);
            }
        }

        return clusters.stream().map(Accumulator::toCluster).toList();
    }

    /**
     * A cluster under construction. Holds its members' normalized vectors so it can
     * both expose a running centroid during the pass and, once closed, recompute
     * each member's similarity against the final centroid.
     */
    private static final class Accumulator {

        private final List<UUID> ids = new ArrayList<>();
        private final List<float[]> vectors = new ArrayList<>();

        void add(UUID id, float[] normalizedVector) {
            ids.add(id);
            vectors.add(normalizedVector);
        }

        float[] centroid() {
            return VectorMath.centroid(vectors);
        }

        EmailCluster toCluster() {
            float[] centroid = centroid();
            List<ClusterMember> members = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                members.add(new ClusterMember(ids.get(i), VectorMath.cosine(vectors.get(i), centroid)));
            }
            return new EmailCluster(centroid, members);
        }
    }
}
