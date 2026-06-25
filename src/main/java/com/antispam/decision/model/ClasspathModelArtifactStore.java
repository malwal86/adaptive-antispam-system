package com.antispam.decision.model;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

/**
 * Serves model artifacts bundled in the application jar (story 10.04): the bootstrap model — and any
 * model checked into {@code src/main/resources/models} — under
 * {@code /models/spam-classifier-<version>.onnx} with its {@code .metadata.json} sidecar. This is the
 * always-available store: it needs no network and no credentials, so a deployment with no remote
 * storage configured still boots and serves the bootstrap model. The {@link CompositeModelArtifactStore}
 * consults it first; a promoted candidate that is not in the jar misses here and falls through to
 * remote storage.
 *
 * <p>A miss is a {@link ModelArtifactNotFoundException} so the composite can distinguish "not here,
 * try the next store" from a genuine read failure — the same reason {@link ModelArtifactStore} forbids
 * a null return.
 */
@Component
public class ClasspathModelArtifactStore implements ModelArtifactStore {

    private static final String MODEL_RESOURCE_FORMAT = "/models/spam-classifier-%s.onnx";
    private static final String METADATA_RESOURCE_FORMAT = "/models/spam-classifier-%s.metadata.json";

    @Override
    public byte[] modelBytes(String modelVersion) {
        return read(String.format(MODEL_RESOURCE_FORMAT, modelVersion), modelVersion, "ONNX");
    }

    @Override
    public byte[] metadataBytes(String modelVersion) {
        return read(String.format(METADATA_RESOURCE_FORMAT, modelVersion), modelVersion, "metadata");
    }

    private byte[] read(String resource, String modelVersion, String what) {
        try (InputStream in = ClasspathModelArtifactStore.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ModelArtifactNotFoundException(
                        "no classpath " + what + " for model version " + modelVersion
                                + " (expected " + resource + ")");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            // A classpath resource that exists but won't read is a packaging defect, not an absence.
            throw new ModelArtifactNotFoundException(
                    "failed to read classpath " + what + " for model version " + modelVersion, e);
        }
    }
}
