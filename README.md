# Living Anti-Spam System

A production-shaped, self-adapting email-abuse defense — **Java Spring Boot + AI**.
Implemented so far:

- **01.01 — walking skeleton:** a single Spring Boot process that boots, exposes
  health/info probes, fails fast on misconfiguration, and is ready to deploy.
- **01.02 — immutable ingest:** `POST /emails` stores every message as an
  immutable, byte-faithful canonical record in Postgres (DB-trigger-enforced —
  UPDATE/DELETE/TRUNCATE are rejected), idempotent on content hash, retrievable
  via `GET /emails/{id}`.
- **01.03 — dataset seed:** a labeled corpus (ham/spam/phish) loaded through the
  ingest path with high-confidence `ground_truth_labels`; idempotent re-seeding.
- **01.04 — hard-rule engine:** denylisted-URL and brand-spoof rules short-circuit
  the pipeline to `block`/`quarantine` (model skipped), persisted to
  `classifications` with reason codes and `route_used`.
- **01.05 — single-email analyzer + console:** `POST /analyze` runs the pipeline
  on a pasted email (or a picked seed sample, by id) and returns
  `{tier, reasonCodes, routeUsed, latencyMs, explanation}`, persisted and
  refetchable. A separate **Next.js console** (`console/`) renders it as an
  animated, colour-coded result card.

Every later slice (feature extraction, reputation, classifier, LLM fallback,
richer console) builds on this spine.

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

## Analyzer API (story 01.05)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/analyze` | Decide an email and persist the verdict. JSON `{"raw":"<rfc822>","source":"..."}` (paste path) **or** `{"emailId":"<uuid>"}` (analyse an already-ingested email, e.g. a picked seed sample). Also accepts a raw `text/plain`/`message/rfc822` body. Returns `{emailId, classificationId, tier, reasonCodes[], routeUsed, latencyMs, explanation, decidedAt, duplicate}`. `400` for empty paste, `404` for unknown id. |
| `GET` | `/analyze/{emailId}` | The latest persisted decision for an email (proves durability on refetch). `404` if never decided. |
| `GET` | `/seed/samples?perLabel=N` | Labeled seed samples for the picker: `[{emailId, label, dataset, subject, senderDomain}]`, balanced across `ham`/`spam`/`phish` (no address PII). |

`tier` is one of `allow`/`warn`/`quarantine`/`block`; `routeUsed` is `hard_rule`
or `model`. The reason codes are the closed machine vocabulary; the `explanation`
is a grounded one-liner derived from them. JSON uses the repo's camelCase
convention (as in the ingest API). The decision is written to `classifications`,
so it is durable, not merely rendered.

CORS: the analyzer/console is a **separate Next.js service**; the browser origin(s)
it is served from are allow-listed via `antispam.console.allowed-origins`
(env `ANTISPAM_CONSOLE_ALLOWED_ORIGINS`, comma-separated). Default allows the
local Next dev server (`http://localhost:3000`).

## Console (story 01.05 / Epic 12)

The single-email analyzer UI is a thin Next.js client in [`console/`](console/) —
paste or pick an email and watch the decision render as an animated, colour-coded
result card (tier, reason chips, route, latency, explanation). Run the Java API,
then:

```bash
cd console && npm install && npm run dev   # http://localhost:3000 → API on :8080
```

See [`console/README.md`](console/README.md) for stack, tests, and configuration.

### Privacy posture

Principle: **protect at rest, redact at egress.** The canonical store keeps raw,
byte-faithful messages (needed for replay/retrain) and is never redacted —
redaction happens on the way out:

- `GET /emails/{id}` is **redacted by default**: sender/recipient local-parts are
  masked (`n***@deals.example.net`) and the raw body is omitted. The domain is
  kept (it is the reputation key). The full record is opt-in via `?reveal=true`,
  and the verbatim bytes via `/emails/{id}/raw` — both privileged views to be
  gated by authz once it exists.
- **Logs never contain raw bodies or unmasked addresses** — see
  `com.antispam.privacy.Redaction`, used by the ingest audit log.

Still on the roadmap (see the standards discussion): encryption-at-rest +
crypto-shredding for erasure, and PII masking before any LLM egress (Epic 05),
data export (10.01), and the console (Epic 12).

## Seeding the corpus (story 01.03)

A labeled body of real mail drives features, training, replay, and the demo.

```bash
make seed            # offline: load the vendored sample corpus (seed-corpus/)
make seed-download   # fetch the real public corpora (SpamAssassin / Enron / phishing), normalize, load
```

Both load through the **normal ingest path** (`emails.ingest_source = 'seed'`) and
record a high-confidence `ground_truth_labels` row per email (`ham` / `spam` /
`phish`) plus the originating dataset. The corpus is a directory tree
`seed-corpus/<dataset>/<class>/<file>` — `.eml`/raw files are one message each;
`.mbox` files are split. Loading is **idempotent**: re-running adds zero duplicate
emails (content-hash dedupe) and logs a per-class breakdown.

Sources, licensing, and provenance — including why the committed files are
synthetic samples — are documented in [`seed-corpus/README.md`](seed-corpus/README.md).
Seeding requires a reachable Postgres (`docker compose up -d db`).

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
| `ANTISPAM_CONSOLE_ALLOWED_ORIGINS` | `antispam.console.allowed-origins` | `https://console.example` | CORS allow-list for the console (comma-separated; defaults to `http://localhost:3000`) |
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

The two pieces deploy to the platform each fits best — **API → Render** (Docker),
**console → Vercel** (Next.js). Full step-by-step setup is in
[`DEPLOYMENT.md`](DEPLOYMENT.md); in brief:

- **`Dockerfile`** — Java API: multi-stage build (JDK 21 build → JRE 21 runtime),
  non-root, binds the host-injected `PORT`. Deployed on Render.
- **`render.yaml`** — Render blueprint for the API service only (`autoDeploy:
  false` — CI gates deploys). Postgres is **Supabase**; set `APP_POSTGRES_*` and
  `ANTISPAM_CONSOLE_ALLOWED_ORIGINS` in the Render dashboard.
- **Console → Vercel** — root directory `console/`, env `NEXT_PUBLIC_API_BASE_URL`
  = the Render API URL. Vercel auto-builds every push: a **preview** deploy per
  branch/PR and **production** on `main`. The API's CORS allows `https://*.vercel.app`
  so preview URLs work live too. (`console/Dockerfile` also exists for Docker hosts.)
- **`.github/workflows/ci.yml`** — on every push/PR runs the Java build/test and a
  console job (Vitest + build + Playwright E2E); on a green `main` build it
  triggers the Render deploy hook (`RENDER_DEPLOY_HOOK_URL` secret) and then
  **smoke-tests the live API** — polls `/info` until the new commit is serving,
  then asserts a real `/analyze` decision (`LIVE_API_URL` variable).

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
  decision/                        Hard-rule engine + decision pipeline (01.04)
  analyze/                         POST /analyze + GET /analyze/{id} (01.05)
    AnalyzeService.java                       Ingest-or-load → decide → persist
    AnalysisExplainer.java                    Grounded reason-code → sentence
    web/AnalyzeController.java                 Analyzer endpoints
  seed/                            Labeled corpus seed (01.03) + sample picker (01.05)
    web/SeedSampleController.java             GET /seed/samples
  config/WebCorsConfig.java        CORS allow-list for the console
src/main/resources/
  application.yml                   Common config; actuator remapped to /health, /info
  application-local.yml             Local placeholders + datasource creds
  db/migration/V1__create_emails.sql       Schema + immutability trigger (Flyway)
src/test/java/com/antispam/         Acceptance tests (see Test section)
console/                            Next.js analyzer console (story 01.05; Epic 12)
Dockerfile, console/Dockerfile, render.yaml, .github/workflows/ci.yml   Deploy
```

> Project context and the full backlog live under `planning/` (gitignored local
> working context, not committed).
