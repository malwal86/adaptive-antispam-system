package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.antispam.config.SupabaseStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Pins the Supabase Storage request the store makes (story 10.04) without a network call: the exact
 * object path CI staged the candidate at, the GET verb, and the service-role bearer token. The live
 * fetch against real Supabase is a hosted smoke check; this is the achievable local guard that the
 * request shape cannot silently drift from {@code scripts/stage-candidate.sh}'s upload layout.
 */
class SupabaseModelArtifactStoreTest {

    private static final String BASE_URL = "https://project.supabase.co";
    private static final String SERVICE_KEY = "service-role-key";
    private static final String BUCKET = "models";
    private static final byte[] ONNX_BYTES = {1, 2, 3, 4};

    private SupabaseStorageProperties properties() {
        return new SupabaseStorageProperties(BASE_URL, SERVICE_KEY, BUCKET);
    }

    @Test
    void fetches_the_candidate_onnx_with_the_bearer_token_at_the_staged_path() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseModelArtifactStore store = new SupabaseModelArtifactStore(builder, properties());

        server.expect(requestTo(BASE_URL
                        + "/storage/v1/object/models/candidates/cand-v1/spam-classifier-cand-v1.onnx"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + SERVICE_KEY))
                .andRespond(withSuccess(ONNX_BYTES, MediaType.APPLICATION_OCTET_STREAM));

        assertThat(store.modelBytes("cand-v1")).isEqualTo(ONNX_BYTES);
        server.verify();
    }

    @Test
    void fetches_the_metadata_sidecar_at_the_staged_path() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseModelArtifactStore store = new SupabaseModelArtifactStore(builder, properties());

        server.expect(requestTo(BASE_URL
                        + "/storage/v1/object/models/candidates/cand-v1/spam-classifier-cand-v1.metadata.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"trainingBaseRate\":0.5}".getBytes(),
                        MediaType.APPLICATION_JSON));

        assertThat(store.metadataBytes("cand-v1")).isNotEmpty();
        server.verify();
    }

    @Test
    void reports_not_found_when_the_object_is_absent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseModelArtifactStore store = new SupabaseModelArtifactStore(builder, properties());

        server.expect(requestTo(BASE_URL
                        + "/storage/v1/object/models/candidates/ghost-v9/spam-classifier-ghost-v9.onnx"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> store.modelBytes("ghost-v9"))
                .isInstanceOf(ModelArtifactNotFoundException.class)
                .hasMessageContaining("ghost-v9");
    }
}
