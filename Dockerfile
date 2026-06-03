# ---- Build stage: compile + package the bootJar with the pinned JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the wrapper + build scripts first so dependency resolution is cached
# independently of source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

# Full context (including .git, so bootBuildInfo can stamp the commit into /info).
COPY . .
RUN ./gradlew --no-daemon clean bootJar

# ---- Runtime stage: slim JRE image running the jar ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /app/build/libs/*.jar app.jar

# Host injects PORT; the app reads it (see application.yml). 8080 is the local default.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
