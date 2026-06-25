package com.antispam.decision.model;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.antispam.decision.ModelScores;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serves the bootstrap spam/phishing classifier in-process via ONNX Runtime — "train where
 * it's easy (Python), serve where it's fast (Java)" (PRD §Architecture, story 04.01). The
 * model trained and exported by {@code ml/train_classifier.py} is loaded once at startup from
 * the classpath and scored on the synchronous decision path, so there is no model-server
 * network hop in the <100ms budget.
 *
 * <p>The model is a 3-class classifier over {@code [ham, spam, phish]}; the served scores are
 * {@code P(spam)} and {@code P(phish)} read from columns 1 and 2 of the probability tensor.
 *
 * <p><b>Role after Epic 10.</b> This bean is the always-present bootstrap model: it owns the
 * shared {@link OrtEnvironment} interaction at startup and the bootstrap session. The
 * {@link ModelRegistry} seeds its per-version cache from this bean's session rather than loading
 * a second copy, and {@link ModelCalibrationService} still calibrates this bootstrap model
 * directly. Promoted candidates are loaded lazily by the registry — never here. The actual
 * inference and session-build logic lives in {@link OnnxInference}, shared with the registry so
 * the bootstrap and promoted paths run byte-identical code.
 *
 * <p><b>Thread-safety.</b> {@link OrtSession#run} is safe to call concurrently and the session is
 * immutable after construction, so this single bean serves every request thread.
 */
@Component
public class OnnxModel {

    private static final Logger log = LoggerFactory.getLogger(OnnxModel.class);

    /**
     * Identifier of the bootstrap artifact, recorded as {@code model_version} on every
     * model-route decision made under the bootstrap policy. Must match the {@code MODEL_VERSION}
     * in {@code ml/train_classifier.py} and the {@code .onnx} filename below.
     */
    public static final String MODEL_VERSION = "bootstrap-v1";

    private static final String MODEL_RESOURCE = "/models/spam-classifier-" + MODEL_VERSION + ".onnx";

    private final OrtEnvironment environment;
    private final OrtSession session;

    public OnnxModel() {
        // Must run before OrtEnvironment is first referenced: it stages ORT's native
        // libraries so they load from inside a Spring Boot bootJar (see the helper).
        OnnxNativeLibraries.ensureLoadable();
        this.environment = OrtEnvironment.getEnvironment();
        this.session = OnnxInference.buildSession(this.environment, readModelBytes(), MODEL_RESOURCE);
        log.info("loaded ONNX model version={} from {}", MODEL_VERSION, MODEL_RESOURCE);
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
     * Scores one feature vector and returns the model's spam/phishing probabilities stamped with
     * {@link #MODEL_VERSION}.
     *
     * @param vector the model input, length {@link ModelFeatureVector#FEATURE_COUNT}
     * @return the raw (uncalibrated) {@link ModelScores}
     * @throws IllegalArgumentException if {@code vector} is null or the wrong length
     * @throws IllegalStateException    if inference fails
     */
    public ModelScores score(float[] vector) {
        return OnnxInference.score(environment, session, vector, MODEL_VERSION);
    }

    /**
     * The loaded bootstrap session, for the {@link ModelRegistry} to seed its cache with rather than
     * loading a second copy of the bootstrap model into the container's memory budget. Package-private:
     * only the registry, a same-package collaborator, may reuse it; its lifecycle stays owned here
     * (this bean closes it).
     */
    OrtSession session() {
        return session;
    }

    @PreDestroy
    void close() throws OrtException {
        // The OrtEnvironment is process-global and shared; only the session is ours to release.
        session.close();
    }
}
