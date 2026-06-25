package com.antispam.decision.model;

/**
 * Where a model version's artifacts come from (story 10.04): the raw ONNX graph and the training
 * metadata sidecar, addressed by {@code model_version}. This is the seam that lets the serving path
 * load a model it was not shipped with — the bootstrap model travels in the jar (classpath), while a
 * retrain candidate promoted live is fetched from remote object storage. Callers — the
 * {@link ModelRegistry} that builds inference sessions and {@link ModelMetadata} that reads
 * {@code π_train} — ask for bytes by version and stay ignorant of where the bytes live.
 *
 * <p>Returning bytes rather than a stream keeps the contract small and the implementations free to
 * choose their transport (classpath resource, HTTP GET) without leaking a resource the caller must
 * remember to close. A version that exists in no backing store is a
 * {@link ModelArtifactNotFoundException}, not a {@code null} — absence is an error the promotion path
 * must surface, never a silent empty result that would later fail obscurely at session build.
 */
public interface ModelArtifactStore {

    /**
     * The ONNX graph bytes for {@code modelVersion}.
     *
     * @throws ModelArtifactNotFoundException if no store holds an ONNX for the version
     */
    byte[] modelBytes(String modelVersion);

    /**
     * The training metadata sidecar bytes ({@code spam-classifier-<version>.metadata.json}) for
     * {@code modelVersion} — the JSON carrying {@code π_train} that fusion needs.
     *
     * @throws ModelArtifactNotFoundException if no store holds metadata for the version
     */
    byte[] metadataBytes(String modelVersion);
}
