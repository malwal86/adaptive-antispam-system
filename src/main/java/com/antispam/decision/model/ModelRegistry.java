package com.antispam.decision.model;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.antispam.decision.ModelScores;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Holds one ONNX inference session per {@code model_version} and scores against any of them on demand
 * (story 10.04). This is what lets the served model change without a redeploy: when a promotion flips
 * the active policy to a candidate, the first decision that asks for that version finds no session
 * cached and <b>lazy-loads</b> it from the {@link ModelArtifactStore}; every subsequent decision under
 * that version is a cache hit. A rollback to a version still cached is instant; to an evicted one,
 * a single reload.
 *
 * <p><b>The cache is the "no per-email hot-swap" guarantee</b> (AC 4): a session is loaded once per
 * version and reused, so scoring is never a per-email model swap. The only thing that changes which
 * version is asked for is a deliberate policy activation upstream ({@link ServedModel}); there is no
 * method here that swaps a model for an email or payload. Model changes are discrete and policy-driven,
 * exactly as the PRD's non-goal requires.
 *
 * <p>The bootstrap session is not loaded twice: the registry seeds its cache from the already-loaded
 * {@link OnnxModel} bean, so a bootstrap-only deployment carries exactly one classifier session on the
 * 512MB box. Promoted sessions add memory only when actually served, bounded by how many versions are
 * activated in a process's life.
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final OrtEnvironment environment;
    private final ModelArtifactStore artifactStore;

    /** version -> session. Seeded with the bootstrap session; promoted versions added lazily. */
    private final ConcurrentHashMap<String, OrtSession> sessions = new ConcurrentHashMap<>();

    /**
     * Versions whose sessions this registry created (and therefore must close at shutdown). The
     * bootstrap session is owned and closed by {@link OnnxModel}, so it is deliberately absent here.
     */
    private final Set<String> ownedVersions = ConcurrentHashMap.newKeySet();

    @Autowired
    public ModelRegistry(OnnxModel bootstrap, ModelArtifactStore artifactStore) {
        // Idempotent; already run by OnnxModel's constructor, repeated here so the registry never
        // touches the environment before native libs are staged regardless of bean init order.
        OnnxNativeLibraries.ensureLoadable();
        this.environment = OrtEnvironment.getEnvironment();
        this.artifactStore = artifactStore;
        // Reuse the bootstrap bean's session rather than loading a second copy.
        this.sessions.put(OnnxModel.MODEL_VERSION, bootstrap.session());
    }

    /**
     * Scores {@code vector} with the model identified by {@code modelVersion}, lazy-loading that
     * version's session on first use and reusing it thereafter.
     *
     * @return the raw (uncalibrated) {@link ModelScores}, stamped with {@code modelVersion}
     * @throws ModelArtifactNotFoundException if the version is not in any artifact store
     * @throws IllegalArgumentException       if {@code vector} is null or the wrong length
     * @throws IllegalStateException          if the model fails to load or inference fails
     */
    public ModelScores score(String modelVersion, float[] vector) {
        OrtSession session = sessions.computeIfAbsent(modelVersion, this::loadSession);
        return OnnxInference.score(environment, session, vector, modelVersion);
    }

    /** The versions whose sessions are currently loaded — for runtime observability (AC 5). */
    public Set<String> loadedVersions() {
        return Set.copyOf(sessions.keySet());
    }

    private OrtSession loadSession(String modelVersion) {
        byte[] modelBytes = artifactStore.modelBytes(modelVersion);
        OrtSession session = OnnxInference.buildSession(environment, modelBytes, modelVersion);
        ownedVersions.add(modelVersion);
        log.info("lazy-loaded ONNX model version={} ({} bytes) into the serving registry",
                modelVersion, modelBytes.length);
        return session;
    }

    @PreDestroy
    void close() {
        // Close only the sessions this registry created; the bootstrap session belongs to OnnxModel.
        for (String version : ownedVersions) {
            OrtSession session = sessions.get(version);
            if (session == null) {
                continue;
            }
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("failed to close ONNX session for model version {}", version, e);
            }
        }
    }
}
