package com.antispam.decision.model;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.antispam.decision.ModelScores;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * The shared ONNX inference primitives for the spam/phishing classifier (story 04.01, generalised in
 * 10.04): how a session is built from raw model bytes, and how one feature vector is scored into
 * {@link ModelScores}. Extracted so the eagerly-loaded bootstrap model ({@link OnnxModel}) and the
 * lazily-loaded promoted models ({@link ModelRegistry}) run <em>identical</em> code — the load-bearing
 * detail being the output column order ({@code [ham, spam, phish]}), which must be single-sourced so a
 * promoted model can never be read with a different convention than the bootstrap one.
 *
 * <p>Stateless and static: every call allocates and closes its own tensor and result, so a session is
 * safe to score concurrently (ORT's {@link OrtSession#run} is thread-safe).
 */
final class OnnxInference {

    /** The exporter names the single input tensor "input" (see {@code ml/train_classifier.py}). */
    private static final String INPUT_NAME = "input";

    /** The float [n,3] probability tensor; column order is [ham, spam, phish]. */
    private static final String PROBABILITIES_OUTPUT = "probabilities";
    private static final int SPAM_COLUMN = 1;
    private static final int PHISH_COLUMN = 2;

    private OnnxInference() {
    }

    /**
     * Builds an inference session from raw ONNX bytes with the lean options the small model and small
     * container need.
     *
     * @param describeFor a human-readable identifier of what is being loaded, used only in the error
     *                    message if the bytes are not a loadable graph
     * @throws IllegalStateException if the bytes are not a valid/loadable ONNX graph
     */
    static OrtSession buildSession(OrtEnvironment environment, byte[] modelBytes, String describeFor) {
        try (OrtSession.SessionOptions options = leanOptions()) {
            return environment.createSession(modelBytes, options);
        } catch (OrtException e) {
            // A model that won't load is a packaging/export defect, not a runtime condition to recover
            // from — fail loudly rather than serve a half-initialized classifier.
            throw new IllegalStateException("failed to create ONNX session for " + describeFor, e);
        }
    }

    /**
     * Session options tuned for a tiny model on a small, shared container. By default ONNX Runtime
     * sizes its thread pool to the host CPU count and reserves an allocation arena — on a 0.5-CPU /
     * 512MB instance that memory and those thread stacks can OOM the process. This model is a single
     * matrix-multiply, so one thread is plenty and the arena's upfront reservation buys nothing.
     */
    private static OrtSession.SessionOptions leanOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.setCPUArenaAllocator(false);
        options.setMemoryPatternOptimization(false);
        return options;
    }

    /**
     * Scores one feature vector against {@code session} and returns the model's spam/phishing
     * probabilities stamped with {@code modelVersion}.
     *
     * @param vector the model input, length {@link ModelFeatureVector#FEATURE_COUNT}
     * @throws IllegalArgumentException if {@code vector} is null or the wrong length
     * @throws IllegalStateException    if inference fails
     */
    static ModelScores score(OrtEnvironment environment, OrtSession session, float[] vector,
            String modelVersion) {
        if (vector == null || vector.length != ModelFeatureVector.FEATURE_COUNT) {
            throw new IllegalArgumentException(
                    "vector must have length " + ModelFeatureVector.FEATURE_COUNT
                            + " but was " + (vector == null ? "null" : vector.length));
        }
        long[] shape = {1, ModelFeatureVector.FEATURE_COUNT};
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(vector), shape);
                OrtSession.Result result = session.run(Map.of(INPUT_NAME, input))) {
            float[][] probabilities = (float[][]) result.get(PROBABILITIES_OUTPUT)
                    .orElseThrow(() -> new IllegalStateException(
                            "model output missing '" + PROBABILITIES_OUTPUT + "'"))
                    .getValue();
            float[] row = probabilities[0];
            return new ModelScores(row[SPAM_COLUMN], row[PHISH_COLUMN], modelVersion);
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed for model version " + modelVersion, e);
        }
    }
}
