# Living Anti-Spam System

A production-shaped, self-adapting email-abuse defense — **Java Spring Boot + AI**.
Implemented so far:

- **01.01 — walking skeleton:** a single Spring Boot process that boots, exposes
  health/info probes, fails fast on misconfiguration, and is ready to deploy.
- **01.02 — immutable ingest:** `POST /emails` stores every message as an
  immutable, byte-faithful canonical record in Postgres (DB-trigger-enforced —
  UPDATE/DELETE/TRUNCATE are rejected), idempotent on content hash, retrievable
  via `GET /emails/{id}`.

Every later slice (feature extraction, reputation, classifier, LLM fallback,
console) builds on this spine.

## Requirements

- A JDK is **not** required to be pre-installed at version 21 — the Gradle
  toolchain auto-provisions JDK 21 on first build. (Any JDK that can run
  Gradle 8.14 is enough to bootstrap.)
- Network access on first build (to download Gradle, JDK 21, and dependencies).

## Local run

As of story 01.02 the app persists to Postgres on startup (Flyway applies the
schema), so a local Postgres must be reachable. Bring one up with Docker, then run:

```bash
docker compose up -d db                                      # local Postgres on :5432
./gradlew bootRun --args='--spring.profiles.active=local'
```

Then:

```bash
curl localhost:8080/health      # -> {"status":"UP"}
curl localhost:8080/info        # -> {"build":{"version":"0.1.0","commit":"<sha>",...}}

# Ingest a raw email and read it back, byte-faithful:
ID=$(curl -s -H 'Content-Type: message/rfc822' --data-binary @some.eml \
      localhost:8080/emails | python3 -c 'import sys,json;print(json.load(sys.stdin)["emailId"])')
curl -s localhost:8080/emails/$ID          # JSON: parsed metadata + rawBase64
curl -s localhost:8080/emails/$ID/raw      # the exact bytes you posted
```

Cold start to a healthy `/health` is well under 30s.

## Ingest API (story 01.02)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/emails` | Body is a raw RFC-822 email (`Content-Type: message/rfc822`, `text/plain`, or `application/octet-stream`) **or** JSON `{"raw":"<rfc822>","source":"..."}`. Optional `X-Ingest-Source` header. Returns `201` + `Location` for a new record, `200` for a duplicate (same content hash). |
| `GET` | `/emails/{id}` | JSON: parsed metadata (sender, domain, recipients, subject, received-at, auth-results) + `rawBase64` (byte-faithful). `404` if unknown. |
| `GET` | `/emails/{id}/raw` | The original bytes verbatim (`message/rfc822`). |

The `emails` table is immutable: a Postgres trigger rejects any UPDATE, DELETE,
or TRUNCATE, so the canonical record can never be silently rewritten.

## Test

```bash
./gradlew test
```

- **Unit (no Docker):** `EmailParserTest` (RFC-822 extraction incl. malformed
  headers), `RequiredServicesPropertiesTest` (fail-fast config).
- **Integration / e2e (require Docker):** full-context tests run against a real
  Postgres via [Testcontainers](https://testcontainers.com) — byte-faithful
  round-trip, idempotent re-ingest, DB-enforced immutability
  (`EmailIngestPersistenceTest`), HTTP ingest/retrieve (`EmailIngestApiTest`),
  health/info (`HealthAndInfoEndpointTest`), and context load
  (`AntiSpamApplicationTests`). These **skip automatically** when no Docker daemon
  is present, and run in full in CI.

## Configuration (env / profiles — no secrets in the repo)

The managed-service coordinates are **required** and read from the environment
(relaxed binding maps the env vars below onto the `app.*` properties). A missing
or blank value aborts startup with a clear message — the process never boots
half-configured.

| Env var | Property | Example | Service |
|---|---|---|---|
| `APP_POSTGRES_URL` | `app.postgres-url` / `spring.datasource.url` | `jdbc:postgresql://host:5432/antispam` | Supabase Postgres (source of truth) |
| `APP_POSTGRES_USER` | `spring.datasource.username` | `postgres` | Postgres username |
| `APP_POSTGRES_PASSWORD` | `spring.datasource.password` | `••••` | Postgres password (secret — env only) |
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
docker-compose.yml                 Local Postgres for development
src/main/java/com/antispam/
  AntiSpamApplication.java          Spring Boot entry point
  config/RequiredServicesProperties.java  Validated, fail-fast service config
  ingest/                          Immutable email ingest (story 01.02)
    EmailParser.java, ParsedEmail.java     RFC-822 header extraction
    EmailRepository.java, Email.java        Append-only JDBC access
    IngestService.java, IngestResult.java   Hash + parse + idempotent persist
    web/EmailController.java                 POST /emails, GET /emails/{id}[/raw]
src/main/resources/
  application.yml                   Common config; actuator remapped to /health, /info
  application-local.yml             Local placeholders + datasource creds
  db/migration/V1__create_emails.sql       Schema + immutability trigger (Flyway)
src/test/java/com/antispam/         Acceptance tests (see Test section)
Dockerfile, .dockerignore, render.yaml, .github/workflows/ci.yml   Deploy
```

> Project context and the full backlog live under `planning/` (gitignored local
> working context, not committed).
