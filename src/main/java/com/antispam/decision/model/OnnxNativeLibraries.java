package com.antispam.decision.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes ONNX Runtime's bundled native libraries loadable from a Spring Boot
 * executable jar. ONNX Runtime ships its native libraries <em>inside</em> the
 * {@code onnxruntime} jar and, at startup, tries to extract them from the
 * classpath. That extraction does not work when the jar is nested inside a Spring
 * Boot bootJar — ORT fails with {@code UnsatisfiedLinkError: no onnxruntime in
 * java.library.path}, so the app crashes on boot even though the (exploded-classpath)
 * tests pass.
 *
 * <p>The fix uses ORT's own escape hatch: it will load its libraries from the
 * directory named by the {@code onnxruntime.native.path} system property. We read
 * the platform's two libraries off the classpath ourselves — which <em>does</em>
 * work from a nested jar via {@link Class#getResourceAsStream} — copy them to a
 * temp directory, and point that property at it. This must run before
 * {@code OrtEnvironment} is first referenced, so {@link OnnxModel} invokes
 * {@link #ensureLoadable()} at the very top of its constructor.
 */
final class OnnxNativeLibraries {

    private static final Logger log = LoggerFactory.getLogger(OnnxNativeLibraries.class);

    /** ORT's directory override; see the ONNX Runtime Java loader. */
    private static final String NATIVE_PATH_PROPERTY = "onnxruntime.native.path";

    /** Base classpath location of the per-platform native libraries in the ORT jar. */
    private static final String RESOURCE_BASE = "/ai/onnxruntime/native/";

    /** The two libraries ORT loads: the runtime and its JNI bridge. */
    private static final String[] LIBRARY_STEMS = {"onnxruntime", "onnxruntime4j_jni"};

    private static boolean prepared;

    private OnnxNativeLibraries() {
    }

    /**
     * Extracts the platform's ONNX Runtime native libraries to a temp directory and
     * sets {@code onnxruntime.native.path}, unless that property is already set (so
     * an operator-supplied path, or a prior call, wins). Idempotent.
     *
     * @throws IllegalStateException if the platform is unsupported or extraction fails
     */
    static synchronized void ensureLoadable() {
        if (prepared || System.getProperty(NATIVE_PATH_PROPERTY) != null) {
            prepared = true;
            return;
        }
        Platform platform = Platform.current();
        try {
            Path dir = Files.createTempDirectory("onnxruntime-native");
            dir.toFile().deleteOnExit();
            for (String stem : LIBRARY_STEMS) {
                extract(platform, stem, dir);
            }
            System.setProperty(NATIVE_PATH_PROPERTY, dir.toAbsolutePath().toString());
            log.info("staged ONNX Runtime native libraries for {} in {}", platform.dir, dir);
            prepared = true;
        } catch (IOException e) {
            throw new IllegalStateException("failed to stage ONNX Runtime native libraries", e);
        }
    }

    private static void extract(Platform platform, String stem, Path dir) throws IOException {
        String fileName = platform.prefix + stem + platform.extension;
        String resource = RESOURCE_BASE + platform.dir + "/" + fileName;
        try (InputStream in = OnnxNativeLibraries.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("native library not found on classpath: " + resource);
            }
            Path target = dir.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
        }
    }

    /** The {@code <os>-<arch>} resource directory and the platform's library naming. */
    private record Platform(String dir, String prefix, String extension) {

        static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String archDir = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x64";
            if (os.contains("mac") || os.contains("darwin")) {
                return new Platform("osx-" + archDir, "lib", ".dylib");
            }
            if (os.contains("win")) {
                return new Platform("win-" + archDir, "", ".dll");
            }
            if (os.contains("nux") || os.contains("nix")) {
                return new Platform("linux-" + archDir, "lib", ".so");
            }
            throw new IllegalStateException("unsupported platform for ONNX Runtime: os=" + os + " arch=" + arch);
        }
    }
}
