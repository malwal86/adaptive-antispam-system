package com.antispam.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.embedding.EmbeddedEmail;
import com.antispam.decision.embedding.OnnxEmbeddingModel;
import com.antispam.decision.policy.SimHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The story's validation step (06.03 test plan): cluster purity and recall against a
 * labeled mutation family, using the <em>real</em> embedder and the real SimHash so
 * the numbers reflect the shipping model, not a mock. No database — this measures the
 * semantic catch itself; {@code CampaignClusteringIntegrationTest} proves it persists.
 *
 * <p>The labeled family is one shipping-notice campaign reworded across order ids,
 * cities, and phrasing; the distractors are unrelated topics (invoice, security,
 * promo). The assertions are the two success metrics:
 *
 * <ul>
 *   <li><b>Recall = 1.0</b> — every reworded variant lands in one cluster (AC 1).</li>
 *   <li><b>Purity</b> — that cluster holds no distractor, and each distractor is its
 *       own singleton.</li>
 *   <li><b>Beyond surface (AC 2)</b> — reworded pairs in the family sit past the
 *       SimHash near-duplicate radius (06.02), so the cheap surface tier would split
 *       the campaign that the embedding tier keeps whole.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignClusteringValidationTest {

    /** The configured surface near-dup radius (antispam.burst.near-dup-hamming-threshold, 06.02). */
    private static final int NEAR_DUP_HAMMING = 6;

    /** The configured semantic threshold (antispam.campaign-clustering.similarity-threshold). */
    private static final double SIMILARITY_THRESHOLD = 0.80;

    private static final List<String> FAMILY = List.of(
            "your order 1234 has shipped and is on its way to Austin today",
            "your order 5678 has shipped and is on its way to Berlin today",
            "your package 9012 has shipped and is on its way to Denver this morning",
            "your parcel 3456 has shipped and is now on its way to Boston today",
            "your order 7788 has been shipped and is on its way to Chicago today");

    private static final List<String> DISTRACTORS = List.of(
            "your invoice of forty nine dollars is ready to download from your account",
            "security alert a new sign in to your account from Berlin was detected",
            "limited time offer take forty percent off your next purchase today");

    private OnnxEmbeddingModel model;

    @BeforeAll
    void setUp() {
        // Instantiated directly (not the Spring bean) so the test needs no context or
        // Docker; the native session is released when the test JVM exits.
        model = new OnnxEmbeddingModel();
    }

    @Test
    void reworded_family_co_clusters_with_full_recall_and_purity() {
        List<EmbeddedEmail> corpus = new ArrayList<>();
        for (int i = 0; i < FAMILY.size(); i++) {
            corpus.add(new EmbeddedEmail(new UUID(0L, i), model.embed(FAMILY.get(i))));
        }
        for (int i = 0; i < DISTRACTORS.size(); i++) {
            corpus.add(new EmbeddedEmail(new UUID(1L, i), model.embed(DISTRACTORS.get(i))));
        }

        List<EmailCluster> clusters = new CampaignClusterer(SIMILARITY_THRESHOLD).cluster(corpus);

        // Recall: all five reworded variants in exactly one cluster.
        EmailCluster family = clusters.stream()
                .filter(c -> c.members().stream().anyMatch(m -> m.emailId().equals(new UUID(0L, 0))))
                .findFirst()
                .orElseThrow();
        List<UUID> familyIds = new ArrayList<>();
        for (int i = 0; i < FAMILY.size(); i++) {
            familyIds.add(new UUID(0L, i));
        }
        assertThat(family.members()).extracting(ClusterMember::emailId)
                .as("every reworded variant co-clusters (recall = 1.0)")
                .containsExactlyInAnyOrderElementsOf(familyIds);

        // Purity: distractors are not in the family cluster, and each is its own cluster.
        for (int i = 0; i < DISTRACTORS.size(); i++) {
            UUID distractorId = new UUID(1L, i);
            assertThat(family.members()).extracting(ClusterMember::emailId)
                    .doesNotContain(distractorId);
            EmailCluster own = clusters.stream()
                    .filter(c -> c.members().stream().anyMatch(m -> m.emailId().equals(distractorId)))
                    .findFirst()
                    .orElseThrow();
            assertThat(own.size()).as("distractor %d is its own singleton", i).isEqualTo(1);
        }
    }

    @Test
    void family_defeats_surface_near_duplicate_detection() {
        // The semantic tier exists for exactly the variants SimHash misses: reworded
        // enough that their fingerprints are far apart, yet one campaign. Show both a
        // far-apart pair and that the embedding still co-clusters the whole family.
        SimHasher simHasher = new SimHasher();
        long f0 = simHasher.fingerprint(FAMILY.get(0));
        long f2 = simHasher.fingerprint(FAMILY.get(2));
        int hamming = SimHasher.hammingDistance(f0, f2);

        assertThat(hamming)
                .as("reworded variants are beyond the surface near-dup radius")
                .isGreaterThan(NEAR_DUP_HAMMING);

        // ...but in embedding space they are one campaign.
        double cosine = VectorMath.cosine(model.embed(FAMILY.get(0)), model.embed(FAMILY.get(2)));
        assertThat(cosine)
                .as("the same variants are semantically one campaign")
                .isGreaterThanOrEqualTo(SIMILARITY_THRESHOLD);
    }
}
