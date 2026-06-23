package com.antispam.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.antispam.decision.embedding.EmbeddedEmail;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The offline clusterer's behavior (story 06.03 unit plan: assignment to nearest
 * centroid, threshold behavior, determinism). Works in 2-D so each vector is an
 * angle and "semantically similar" is "small angle apart" — the same geometry the
 * real 128-d embeddings live in, but legible. The end-to-end check against the real
 * embedder and pgvector is {@code CampaignClusteringIntegrationTest}.
 */
class CampaignClustererTest {

    /** Stable, order-controlling ids: {@code new UUID(0, n)} sorts ascending by n. */
    private static UUID id(int n) {
        return new UUID(0L, n);
    }

    /** A unit vector at {@code degrees}; cosine of two such vectors is cos(angle between). */
    private static float[] at(double degrees) {
        double r = Math.toRadians(degrees);
        return new float[] {(float) Math.cos(r), (float) Math.sin(r)};
    }

    private static EmbeddedEmail email(int n, double degrees) {
        return new EmbeddedEmail(id(n), at(degrees));
    }

    @Test
    void empty_input_yields_no_clusters() {
        assertThat(new CampaignClusterer(0.8).cluster(List.of())).isEmpty();
    }

    @Test
    void a_single_email_is_its_own_cluster() {
        List<EmailCluster> clusters = new CampaignClusterer(0.8).cluster(List.of(email(1, 0)));
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).members()).extracting(ClusterMember::emailId).containsExactly(id(1));
    }

    @Test
    void similar_emails_group_and_a_distractor_stays_apart() {
        // A reworded family (all within a few degrees → cosine ~0.99) plus one
        // unrelated email at 90° (cosine 0). This is the "reworded variants that
        // defeat surface near-dup still co-cluster" case (AC 1, AC 2), in miniature.
        List<EmailCluster> clusters = new CampaignClusterer(0.9).cluster(List.of(
                email(1, 0), email(2, 4), email(3, 8), email(4, 90)));

        assertThat(clusters).hasSize(2);
        EmailCluster family = clusterContaining(clusters, id(1));
        assertThat(family.members()).extracting(ClusterMember::emailId)
                .containsExactlyInAnyOrder(id(1), id(2), id(3));
        EmailCluster distractor = clusterContaining(clusters, id(4));
        assertThat(distractor.size()).isEqualTo(1);
    }

    @Test
    void an_email_joins_the_nearest_existing_centroid() {
        // Seeds at 0° and 90° are too far to merge (cosine 0 < 0.5). The third email
        // at 80° is near the 90° seed (cos 10° ≈ 0.98) and far from the 0° seed
        // (cos 80° ≈ 0.17), so it joins the 90° cluster, not the 0° one.
        List<EmailCluster> clusters = new CampaignClusterer(0.5).cluster(List.of(
                email(1, 0), email(2, 90), email(3, 80)));

        assertThat(clusters).hasSize(2);
        assertThat(clusterContaining(clusters, id(1)).size()).isEqualTo(1);
        assertThat(clusterContaining(clusters, id(2)).members()).extracting(ClusterMember::emailId)
                .containsExactlyInAnyOrder(id(2), id(3));
    }

    @Test
    void threshold_controls_whether_a_borderline_pair_merges() {
        List<EmbeddedEmail> pair = List.of(email(1, 0), email(2, 45)); // cosine ≈ 0.707

        assertThat(new CampaignClusterer(0.8).cluster(pair)).hasSize(2);  // demands tighter → split
        assertThat(new CampaignClusterer(0.6).cluster(pair)).hasSize(1);  // looser → merged
    }

    @Test
    void member_similarity_is_measured_against_the_final_centroid() {
        // Two emails 60° apart (cosine 0.5 ≥ threshold) merge into one cluster: the
        // centroid sits at 30°, so each member is cos 30° ≈ 0.866 from it, and the
        // two are equal — the similarity reflects the final centroid, not the running
        // one the second email saw at assignment time.
        EmailCluster merged = new CampaignClusterer(0.4)
                .cluster(List.of(email(1, 0), email(2, 60)))
                .get(0);

        assertThat(merged.size()).isEqualTo(2);
        assertThat(merged.members()).allSatisfy(m ->
                assertThat(m.cosineSimilarity()).isCloseTo(Math.cos(Math.toRadians(30)), within(1e-5)));
    }

    @Test
    void centroid_is_unit_length() {
        EmailCluster cluster = new CampaignClusterer(0.5)
                .cluster(List.of(email(1, 10), email(2, 20)))
                .get(0);
        assertThat(VectorMath.cosine(cluster.centroid(), cluster.centroid())).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void clustering_is_deterministic_regardless_of_input_order() {
        List<EmbeddedEmail> emails = List.of(
                email(1, 0), email(2, 3), email(3, 6),       // family A
                email(4, 90), email(5, 93),                   // family B
                email(6, 180));                               // singleton
        List<EmbeddedEmail> shuffled = new ArrayList<>(emails);
        shuffled.sort(Comparator.comparing(EmbeddedEmail::emailId).reversed());

        CampaignClusterer clusterer = new CampaignClusterer(0.95);
        List<EmailCluster> forward = clusterer.cluster(emails);
        List<EmailCluster> reversed = clusterer.cluster(shuffled);

        assertThat(idsOf(forward)).isEqualTo(idsOf(reversed));
        assertThat(membershipOf(forward)).isEqualTo(membershipOf(reversed));
    }

    @Test
    void rejects_threshold_outside_unit_range() {
        assertThatThrownBy(() -> new CampaignClusterer(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampaignClusterer(1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static EmailCluster clusterContaining(List<EmailCluster> clusters, UUID emailId) {
        return clusters.stream()
                .filter(c -> c.members().stream().anyMatch(m -> m.emailId().equals(emailId)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cluster contains " + emailId));
    }

    /** The set of deterministic cluster ids — the reproducible identity of a clustering. */
    private static Set<UUID> idsOf(List<EmailCluster> clusters) {
        return clusters.stream().map(EmailCluster::deterministicId).collect(Collectors.toSet());
    }

    /** Each email mapped to its cluster's deterministic id — the partition itself. */
    private static java.util.Map<UUID, UUID> membershipOf(List<EmailCluster> clusters) {
        java.util.Map<UUID, UUID> map = new java.util.HashMap<>();
        for (EmailCluster c : clusters) {
            for (ClusterMember m : c.members()) {
                map.put(m.emailId(), c.deterministicId());
            }
        }
        return map;
    }
}
