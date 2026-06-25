package com.antispam.decision.model;

import com.antispam.config.SupabaseStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches a promoted retrain candidate's artifacts from Supabase Storage (story 10.04) over the
 * Storage REST API, mirroring exactly where CI staged them: {@code scripts/stage-candidate.sh}
 * uploads each candidate file to {@code candidates/<version>/<name>}, so this reads
 * {@code GET {url}/storage/v1/object/{bucket}/candidates/<version>/spam-classifier-<version>.onnx}
 * (and the {@code .metadata.json} alongside) with the service-role key as a bearer token. It is the
 * fallback store the {@link CompositeModelArtifactStore} consults after the classpath, so it is only
 * ever asked for versions the jar does not carry — the promoted candidates.
 *
 * <p>The bean exists only when {@code app.supabase.url} is set ({@link ConditionalOnProperty}): a
 * deployment with no remote storage has no Supabase store at all and serves the bootstrap model from
 * the classpath, rather than holding a bean that would fail on first use. A missing object (HTTP 404)
 * or any transport failure becomes a {@link ModelArtifactNotFoundException} so the absence reads the
 * same regardless of which store could not find it.
 */
@Component
@ConditionalOnProperty(prefix = "app.supabase", name = "url")
public class SupabaseModelArtifactStore implements ModelArtifactStore {

    private static final String OBJECT_PATH_FORMAT = "/storage/v1/object/%s/candidates/%s/%s";
    private static final String MODEL_FILE_FORMAT = "spam-classifier-%s.onnx";
    private static final String METADATA_FILE_FORMAT = "spam-classifier-%s.metadata.json";

    private final RestClient restClient;
    private final String bucket;

    @Autowired
    public SupabaseModelArtifactStore(RestClient.Builder restClientBuilder,
            SupabaseStorageProperties properties) {
        this.bucket = properties.bucket();
        this.restClient = restClientBuilder
                .baseUrl(properties.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceKey())
                .build();
    }

    @Override
    public byte[] modelBytes(String modelVersion) {
        return fetch(String.format(MODEL_FILE_FORMAT, modelVersion), modelVersion, "ONNX");
    }

    @Override
    public byte[] metadataBytes(String modelVersion) {
        return fetch(String.format(METADATA_FILE_FORMAT, modelVersion), modelVersion, "metadata");
    }

    private byte[] fetch(String fileName, String modelVersion, String what) {
        String path = String.format(OBJECT_PATH_FORMAT, bucket, modelVersion, fileName);
        try {
            byte[] body = restClient.get().uri(path).retrieve().body(byte[].class);
            if (body == null || body.length == 0) {
                throw new ModelArtifactNotFoundException(
                        "empty " + what + " from Supabase for model version " + modelVersion
                                + " at " + path);
            }
            return body;
        } catch (RestClientException e) {
            // 404 (object absent) and transport errors alike: the artifact is not fetchable here, so
            // the composite store treats it as an absence and reports a uniform not-found.
            throw new ModelArtifactNotFoundException(
                    "could not fetch " + what + " from Supabase for model version " + modelVersion
                            + " at " + path, e);
        }
    }
}
