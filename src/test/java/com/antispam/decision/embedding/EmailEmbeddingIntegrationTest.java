package com.antispam.decision.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end of the 04.03 embedding path against a real Postgres + pgvector and the
 * real in-process embedder (no mock): an ingested email is embedded, the vector is
 * persisted to {@code email_embeddings}, and a cosine nearest-neighbor query over
 * pgvector returns it — round-tripping through the database, not just memory
 * (AC 3). Also pins the determinism/idempotency contract: a duplicate delivery
 * neither re-embeds nor duplicates the row.
 */
class EmailEmbeddingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EmailEmbeddingService service;

    @Autowired
    private EmailEmbeddingRepository repository;

    @Autowired
    private IngestService ingestService;

    private UUID ingest(String raw) {
        return ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test").emailId();
    }

    @Test
    void embeds_stores_and_finds_nearest_neighbor() {
        // Three distinct-topic emails; bytes unique to this test so ingest's
        // content-hash idempotency doesn't collide with another test's rows.
        UUID shipping = ingest("""
                Subject: Your order A-9001 has shipped [emb-it-shipping]

                Good news, your parcel A-9001 has shipped and is on its way to Seattle today.
                """);
        UUID billing = ingest("""
                Subject: Invoice A-9002 is ready [emb-it-billing]

                Your invoice A-9002 of 73 dollars is now available to download from your account.
                """);
        UUID security = ingest("""
                Subject: Security alert for your account [emb-it-security]

                Security alert: a new sign in to your account from Berlin was just detected.
                """);

        float[] shippingVector = service.embedAndStore(shipping).orElseThrow();
        float[] billingVector = service.embedAndStore(billing).orElseThrow();
        float[] securityVector = service.embedAndStore(security).orElseThrow();

        // The embedding round-trips through pgvector unchanged.
        Optional<float[]> stored = repository.find(shipping, OnnxEmbeddingModel.EMBEDDING_VERSION);
        assertThat(stored).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.FLOAT_ARRAY)
                .containsExactly(shippingVector);

        // A cosine query with the shipping vector returns the shipping email first, as a
        // near-exact self-match (similarity ~1.0). The suite shares one Postgres, so other
        // tests' embeddings populate the index too; asserting each email is its OWN nearest
        // neighbor is robust to that shared corpus, where a fixed top-N is not.
        List<EmbeddingNeighbor> nearest =
                repository.nearestNeighbors(shippingVector, OnnxEmbeddingModel.EMBEDDING_VERSION, 1);
        assertThat(nearest.get(0).emailId()).isEqualTo(shipping);
        assertThat(nearest.get(0).cosineSimilarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));

        // Billing and security are likewise embedded and queryable — each is its own nearest match.
        assertThat(repository.nearestNeighbors(billingVector, OnnxEmbeddingModel.EMBEDDING_VERSION, 1)
                .get(0).emailId()).isEqualTo(billing);
        assertThat(repository.nearestNeighbors(securityVector, OnnxEmbeddingModel.EMBEDDING_VERSION, 1)
                .get(0).emailId()).isEqualTo(security);
    }

    @Test
    void duplicate_delivery_does_not_re_embed() {
        UUID emailId = ingest("""
                Subject: Password reset code 5150 [emb-it-dupe]

                Use code 5150 to reset the password for your account; it expires soon.
                """);

        Optional<float[]> first = service.embedAndStore(emailId);
        Optional<float[]> second = service.embedAndStore(emailId);

        assertThat(first).isPresent();
        assertThat(second).as("redelivery is claimed by the ledger and skipped").isEmpty();
        assertThat(repository.find(emailId, OnnxEmbeddingModel.EMBEDDING_VERSION)).isPresent();
    }
}
