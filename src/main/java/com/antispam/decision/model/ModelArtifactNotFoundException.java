package com.antispam.decision.model;

/**
 * Thrown when a model artifact (the ONNX graph or its metadata sidecar) for a given
 * {@code model_version} cannot be located in any backing store (story 10.04). A missing
 * artifact is a real, actionable condition — a promotion was attempted for a version whose
 * candidate was never staged, or remote storage is misconfigured — so it is a distinct,
 * named exception rather than a generic failure: the promotion path maps it to a 404 so the
 * caller learns the candidate is not fetchable rather than seeing an opaque 500.
 */
public class ModelArtifactNotFoundException extends RuntimeException {

    public ModelArtifactNotFoundException(String message) {
        super(message);
    }

    public ModelArtifactNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
