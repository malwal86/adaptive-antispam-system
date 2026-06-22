package com.antispam.decision.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.antispam.decision.model.OnnxNativeLibraries;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serves the local sentence embedder in-process via ONNX Runtime (story 04.03).
 * It runs on the <em>same</em> runtime infrastructure as the classifier — one
 * {@link OrtEnvironment}, the shared {@link OnnxNativeLibraries} loader, a second
 * {@link OrtSession} — so the system gets embeddings with zero per-embedding API
 * cost and one runtime to operate (PRD §Architecture: "one runtime serves
 * classifier + embeddings").
 *
 * <p>The model is an LSA encoder exported by {@code ml/train_embedding.py}: it
 * takes the {@link TextHasher#DIMENSION}-wide vector of hashed n-gram counts and
 * returns an L2-normalized {@link #EMBEDDING_DIMENSION}-d vector. Input
 * normalization, the SVD projection, and output normalization all live inside the
 * graph, so {@link #embed} only hashes the text (via {@link TextHasher}) and reads
 * the unit vector back — cosine similarity between two embeddings is their dot
 * product.
 *
 * <p><b>Determinism.</b> Hashing is a pure function and ONNX inference is
 * deterministic, so the same text always yields the same vector — the property
 * dedupe and clustering (Epic 06) rely on. Text with no tokens hashes to zeros and
 * the model maps it to the zero vector.
 *
 * <p><b>Thread-safety.</b> As with the classifier, {@link OrtSession#run} is safe
 * to call concurrently and the session is immutable after construction; each
 * {@link #embed} call allocates and closes its own tensor and result.
 */
@Component
public class OnnxEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingModel.class);

    /**
     * Identifier of the served embedder, recorded as {@code model_version} on every
     * stored embedding. Must match {@code EMBEDDING_VERSION} in
     * {@code ml/embedding_schema.py} and the {@code .onnx} filename below.
     */
    public static final String EMBEDDING_VERSION = "embed-bootstrap-v1";

    /** Dimension of the served embedding; must equal {@code EMBED_DIM} in the Python schema and the pgvector column width. */
    public static final int EMBEDDING_DIMENSION = 128;

    private static final String MODEL_RESOURCE = "/models/" + EMBEDDING_VERSION + ".onnx";

    /** The exporter names the single input tensor "input" (see the training script). */
    private static final String INPUT_NAME = "input";

    private final OrtEnvironment environment;
    private final OrtSession session;

    public OnnxEmbeddingModel() {
        // Idempotent and shared with the classifier: whichever ONNX session is
        // constructed first stages the native libraries; this call is then a no-op.
        OnnxNativeLibraries.ensureLoadable();
        this.environment = OrtEnvironment.getEnvironment();
        this.session = loadSession(this.environment);
        log.info("loaded ONNX embedding model version={} from {}", EMBEDDING_VERSION, MODEL_RESOURCE);
    }

    private static OrtSession loadSession(OrtEnvironment environment) {
        byte[] modelBytes = readModelBytes();
        try (OrtSession.SessionOptions options = leanOptions()) {
            return environment.createSession(modelBytes, options);
        } catch (OrtException e) {
            // A model that won't load is a packaging/export defect, not a runtime
            // condition to recover from — fail fast at startup.
            throw new IllegalStateException("failed to create ONNX session from " + MODEL_RESOURCE, e);
        }
    }

    /**
     * Lean session options, for the same reason as the classifier: a single
     * matrix-multiply on a small, memory-tight container has no use for ORT's
     * multi-threaded pool or its allocation arena, and reserving them risks OOM
     * alongside the JVM and the classifier session (see {@code OnnxModel}).
     */
    private static OrtSession.SessionOptions leanOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.setCPUArenaAllocator(false);
        options.setMemoryPatternOptimization(false);
        return options;
    }

    private static byte[] readModelBytes() {
        try (InputStream in = OnnxEmbeddingModel.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("ONNX embedding model not found on classpath: " + MODEL_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read ONNX embedding model: " + MODEL_RESOURCE, e);
        }
    }

    /**
     * Embeds email text into an L2-normalized {@link #EMBEDDING_DIMENSION}-d vector.
     *
     * @param text email text (subject + body); {@code null} or token-free text
     *             yields the zero vector
     * @return a new {@code float[EMBEDDING_DIMENSION]}
     * @throws IllegalStateException if inference fails
     */
    public float[] embed(String text) {
        float[] hashed = TextHasher.hash(text);
        long[] shape = {1, TextHasher.DIMENSION};
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(hashed), shape);
                OrtSession.Result result = session.run(Map.of(INPUT_NAME, input))) {
            // Single-output graph; read by index to avoid coupling to the exporter's
            // generated output name.
            float[][] embedding = (float[][]) result.get(0).getValue();
            return embedding[0];
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX embedding inference failed", e);
        }
    }

    @PreDestroy
    void close() throws OrtException {
        // The OrtEnvironment is process-global and shared with the classifier; only
        // this session is ours to release.
        session.close();
    }
}
