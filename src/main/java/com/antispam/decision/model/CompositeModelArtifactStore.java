package com.antispam.decision.model;

import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The model artifact store the rest of the system depends on (story 10.04): classpath first, remote
 * Supabase Storage second. The bootstrap model — and the common case where the served model ships in
 * the jar — resolves from the classpath with no network at all; only a promoted candidate the jar does
 * not carry falls through to remote storage. This ordering is what keeps the hot path fast and makes a
 * Supabase outage irrelevant to serving the bundled model.
 *
 * <p>The Supabase store is optional: on a deployment with no {@code app.supabase.url} the bean does not
 * exist, so this composite is injected an empty {@link ObjectProvider} and behaves as a classpath-only
 * store. A version found in neither store is a {@link ModelArtifactNotFoundException} carrying the
 * classpath miss as its cause, so the error names what was actually tried.
 */
@Primary
@Component
public class CompositeModelArtifactStore implements ModelArtifactStore {

    private final ClasspathModelArtifactStore classpath;
    private final ObjectProvider<SupabaseModelArtifactStore> remote;

    @Autowired
    public CompositeModelArtifactStore(ClasspathModelArtifactStore classpath,
            ObjectProvider<SupabaseModelArtifactStore> remote) {
        this.classpath = classpath;
        this.remote = remote;
    }

    @Override
    public byte[] modelBytes(String modelVersion) {
        return classpathOrRemote(modelVersion, classpath::modelBytes,
                ModelArtifactStore::modelBytes, "ONNX");
    }

    @Override
    public byte[] metadataBytes(String modelVersion) {
        return classpathOrRemote(modelVersion, classpath::metadataBytes,
                ModelArtifactStore::metadataBytes, "metadata");
    }

    private byte[] classpathOrRemote(String modelVersion,
            Function<String, byte[]> classpathRead,
            java.util.function.BiFunction<ModelArtifactStore, String, byte[]> remoteRead,
            String what) {
        try {
            return classpathRead.apply(modelVersion);
        } catch (ModelArtifactNotFoundException classpathMiss) {
            SupabaseModelArtifactStore store = remote.getIfAvailable();
            if (store == null) {
                throw new ModelArtifactNotFoundException(
                        "no " + what + " for model version " + modelVersion
                                + " on the classpath and no remote store is configured", classpathMiss);
            }
            return remoteRead.apply(store, modelVersion);
        }
    }
}
