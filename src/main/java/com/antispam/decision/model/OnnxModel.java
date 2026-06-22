package com.antispam.decision.model;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.antispam.decision.ModelScores;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serves the spam/phishing classifier in-process via ONNX Runtime — "train where
 * it's easy (Python), serve where it's fast (Java)" (PRD §Architecture, story
 * 04.01). The model trained and exported by {@code ml/train_classifier.py} is
 * loaded once at startup from the classpath and scored on the synchronous decision
 * path, so there is no model-server network hop in the <100ms budget.
 *
 * <p>The model is a 3-class classifier over {@code [ham, spam, phish]}; the served
 * scores are {@code P(spam)} and {@code P(phish)} read from columns 1 and 2 of the
 * probability tensor. The column order is fixed by the integer labels the training
 * script assigns (see {@code ml/feature_schema.py}).
 *
 * <p><b>Thread-safety.</b> {@link OrtSession#run} is safe to call concurrently, and
 * the session is immutable after construction, so this single bean serves every
 * request thread. Each {@link #score} call allocates and closes its own input
 * tensor and result, holding no per-call state.
 */
@Component
public class OnnxModel {

    private static final Logger log = LoggerFactory.getLogger(OnnxModel.class);

    /**
     * Identifier of the served artifact, recorded as {@code model_version} on every
     * model-route decision. Must match the {@code MODEL_VERSION} in
     * {@code ml/train_classifier.py} and the {@code .onnx} filename below; the
     * retrain loop (Epic 10) bumps all three together when it promotes a new model.
     */
    public static final String MODEL_VERSION = "bootstrap-v1";

    private static final String MODEL_RESOURCE = "/models/spam-classifier-" + MODEL_VERSION + ".onnx";

    /** The exporter names the single input tensor "input" (see the training script). */
    private static final String INPUT_NAME = "input";

    /** The float [n,3] probability tensor; column order is [ham, spam, phish]. */
    private static final String PROBABILITIES_OUTPUT = "probabilities";
    private static final int SPAM_COLUMN = 1;
    private static final int PHISH_COLUMN = 2;

    private final OrtEnvironment environment;
    private final OrtSession session;

    public OnnxModel() {
        // Must run before OrtEnvironment is first referenced: it stages ORT's native
        // libraries so they load from inside a Spring Boot bootJar (see the helper).
        OnnxNativeLibraries.ensureLoadable();
        this.environment = OrtEnvironment.getEnvironment();
        this.session = loadSession(this.environment);
        log.info("loaded ONNX model version={} from {}", MODEL_VERSION, MODEL_RESOURCE);
    }

    private static OrtSession loadSession(OrtEnvironment environment) {
        byte[] modelBytes = readModelBytes();
        try {
            return environment.createSession(modelBytes, new OrtSession.SessionOptions());
        } catch (OrtException e) {
            // A model that won't load is a packaging/export defect, not a runtime
            // condition to recover from — fail fast at startup rather than serve a
            // half-initialized classifier.
            throw new IllegalStateException("failed to create ONNX session from " + MODEL_RESOURCE, e);
        }
    }

    private static byte[] readModelBytes() {
        try (InputStream in = OnnxModel.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("ONNX model not found on classpath: " + MODEL_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read ONNX model: " + MODEL_RESOURCE, e);
        }
    }

    /**
     * Scores one feature vector and returns the model's spam/phishing probabilities
     * stamped with {@link #MODEL_VERSION}.
     *
     * @param vector the model input, length {@link ModelFeatureVector#FEATURE_COUNT}
     * @return the raw (uncalibrated) {@link ModelScores}
     * @throws IllegalArgumentException if {@code vector} is null or the wrong length
     * @throws IllegalStateException    if inference fails
     */
    public ModelScores score(float[] vector) {
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
            return new ModelScores(row[SPAM_COLUMN], row[PHISH_COLUMN], MODEL_VERSION);
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    @PreDestroy
    void close() throws OrtException {
        // The OrtEnvironment is process-global and shared; only the session is ours
        // to release.
        session.close();
    }
}
