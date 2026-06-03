# Living Anti-Spam System

A production-shaped, self-adapting email-abuse defense — **Java Spring Boot + AI**.
This repository currently contains the **walking skeleton** (story 01.01): a single
Spring Boot process that boots, exposes health/info probes, fails fast on
misconfiguration, and is ready to deploy to an always-on hosted URL. Every later
slice (ingest, feature extraction, reputation, classifier, LLM fallback, console)
builds on this spine.

## Requirements

- A JDK is **not** required to be pre-installed at version 21 — the Gradle
  toolchain auto-provisions JDK 21 on first build. (Any JDK that can run
  Gradle 8.14 is enough to bootstrap.)
- Network access on first build (to download Gradle, JDK 21, and dependencies).

## One-command local run

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile supplies placeholder Postgres/Redis/Kafka coordinates so a
fresh clone boots without standing up those services. Then:

```bash
curl localhost:8080/health      # -> {"status":"UP"}
curl localhost:8080/info        # -> {"build":{"version":"0.1.0","commit":"<sha>",...}}
```

Cold start to a healthy `/health` is well under 30s.

## Test

```bash
./gradlew test
```

Covers: context boots (`AntiSpamApplicationTests`), `/health` returns `200`
`{"status":"UP"}` and `/info` reports the build version + commit
(`HealthAndInfoEndpointTest`), and the app fails fast when a required service URL
is missing or blank (`RequiredServicesPropertiesTest`).

## Configuration (env / profiles — no secrets in the repo)

The managed-service coordinates are **required** and read from the environment
(relaxed binding maps the env vars below onto the `app.*` properties). A missing
or blank value aborts startup with a clear message — the process never boots
half-configured.

| Env var | Property | Example | Service |
|---|---|---|---|
| `APP_POSTGRES_URL` | `app.postgres-url` | `jdbc:postgresql://host:5432/antispam` | Supabase Postgres (source of truth) |
| `APP_REDIS_URL` | `app.redis-url` | `redis://host:6379` | Upstash Redis (reputation cache, budget cap) |
| `APP_KAFKA_BOOTSTRAP_SERVERS` | `app.kafka-bootstrap-servers` | `host:9092` | Aiven Kafka (event spine) |
| `PORT` | `server.port` | `8080` | Host-injected listen port (defaults to 8080) |

For local development the `local` profile (`src/main/resources/application-local.yml`)
provides non-secret placeholders for the three service URLs. In any real
environment, set the real values via environment variables — never commit them.

Running without the `local` profile and without the env vars set is the
fail-fast path:

```bash
java -jar build/libs/living-antispam-0.1.0.jar
# APPLICATION FAILED TO START
#   Reason: app.postgres-url (env APP_POSTGRES_URL) must be set
#   ... (exit code 1)
```

## Deploy (always-on hosted demo)

- **`Dockerfile`** — multi-stage build (JDK 21 build → JRE 21 runtime), runs as a
  non-root user, binds the host-injected `PORT`.
- **`render.yaml`** — Render blueprint: Docker web service, `autoDeploy: true` so a
  push to `main` builds + deploys, `healthCheckPath: /health`. Set the three
  `APP_*` env vars in the Render dashboard.
- **`.github/workflows/ci.yml`** — builds + tests on every push/PR; on a green
  `main` build, triggers the hosted deploy (via the `RENDER_DEPLOY_HOOK_URL`
  secret; skipped if unset).

Any host that injects `PORT` (Render / Railway / Fly) works; the app is
host-agnostic.

## Layout

```
build.gradle, settings.gradle      Gradle build (Spring Boot 3.4, Java 21 toolchain)
src/main/java/com/antispam/
  AntiSpamApplication.java          Spring Boot entry point
  config/RequiredServicesProperties.java  Validated, fail-fast service config
src/main/resources/
  application.yml                   Common config; actuator remapped to /health, /info
  application-local.yml             Local placeholders for the required service URLs
src/test/java/com/antispam/         Acceptance tests (see Test section)
Dockerfile, .dockerignore, render.yaml, .github/workflows/ci.yml   Deploy
```

> Project context and the full backlog live under `planning/` (gitignored local
> working context, not committed).
