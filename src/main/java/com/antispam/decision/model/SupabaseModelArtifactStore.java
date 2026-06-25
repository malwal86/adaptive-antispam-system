package com.antispam.decision.model;

import com.antispam.config.SupabaseStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>Optional by configuration, not by bean wiring.</b> When {@code app.supabase.url} is blank (no
 * remote storage — local, dev, tests, a bootstrap-only deployment) this store is unconfigured and every
 * fetch is a clean {@link ModelArtifactNotFoundException}: the deployment serves the bootstrap model
 * from the classpath and a promoted candidate simply is not fetchable. The store guards on the blank
 * URL itself rather than via {@code @ConditionalOnProperty}, because an empty environment variable still
 * satisfies that condition and would leave a {@link RestClient} with no base URL that fails obscurely
 * ("URI with undefined scheme") on first use instead of reporting an honest absence.
 */
@Component
public class SupabaseModelArtifactStore implements ModelArtifactStore {

    private static final String OBJECT_PATH_FORMAT = "/storage/v1/object/%s/candidates/%s/%s";
    private static final String MODEL_FILE_FORMAT = "spam-classifier-%s.onnx";
    private static final String METADATA_FILE_FORMAT = "spam-classifier-%s.metadata.json";

    private final boolean configured;
    private final String bucket;
    private final RestClient restClient;

    @Autowired
    public SupabaseModelArtifactStore(RestClient.Builder restClientBuilder,
            SupabaseStorageProperties properties) {
        this.configured = properties.isConfigured();
        this.bucket = properties.bucket();
        // Build the client only when a base URL is configured; an unconfigured store never makes a
        // request, so it needs no client (and must not hold one with an empty base URL).
        this.restClient = configured
                ? restClientBuilder
                        .baseUrl(properties.url())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceKey())
                        .build()
                : null;
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
        if (!configured) {
            throw new ModelArtifactNotFoundException(
                    "no Supabase storage configured (app.supabase.url is blank); cannot fetch " + what
                            + " for model version " + modelVersion);
        }
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
