# ---- Build stage: compile + package the bootJar with the pinned JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# git is required so bootBuildInfo (build.gradle's gitCommit()) can read the
# commit and stamp it into /info — the JDK base image has no git, so without this
# the commit reports "unknown" and any deployed build is unidentifiable.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# Copy the wrapper + build scripts first so dependency resolution is cached
# independently of source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

# Full context (including .git, so bootBuildInfo can stamp the commit into /info).
COPY . .
# The copied tree is root-owned; mark it safe so `git rev-parse` doesn't bail with
# "detected dubious ownership" and fall back to "unknown".
RUN git config --global --add safe.directory /app
RUN ./gradlew --no-daemon clean bootJar

# ---- Runtime stage: slim JRE image running the jar ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /app/build/libs/*.jar app.jar

# Ship the labeled seed corpus into the image. SeedRunner resolves antispam.seed.corpus-dir
# (default "seed-corpus") as a filesystem path relative to WORKDIR, so without this the
# directory is absent at runtime, seeding loads nothing, and /seed/samples returns [].
COPY --from=build /app/seed-corpus ./seed-corpus

# Host injects PORT; the app reads it (see application.yml). 8080 is the local default.
EXPOSE 8080

# Memory budget for the Render starter instance (512MB / 0.5 CPU). ONNX Runtime
# (story 04.01) allocates native memory and a thread pool *outside* the JVM heap, so
# the JVM must leave room: cap the heap at half of RAM, and pin the process to a
# single CPU so neither the JVM nor ORT sizes its thread pools to the host's many
# cores. Without these the container OOMs on boot and the deploy never goes live.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -XX:ActiveProcessorCount=1"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
