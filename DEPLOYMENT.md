# Deployment & Continuous Delivery

How the Living Anti-Spam System goes live and stays live: **every push to `main`
is tested in CI, then deployed, then smoke-tested against the live URL.**

```
                         GitHub (main)
                              │  push / merged PR
            ┌─────────────────┴──────────────────┐
            ▼                                     ▼
   GitHub Actions CI                       Vercel Git integration
   ├─ Java build + test                    └─ console: preview per branch/PR,
   ├─ console build + e2e                     production on main  (auto)
   └─ on green main:
        ├─ POST Render deploy hook  ──────▶ Render: living-antispam (Docker, Java API)
        └─ smoke test live API (/info commit match → /analyze == block)
```

- **API → Render** (Docker web service). Deploys are **CI-gated** (`autoDeploy:
  false`); the CI deploy hook fires only after a green build.
- **Console → Vercel** (Next.js). Vercel builds **every** push automatically:
  a preview deployment per branch/PR and production on `main`.
- **Postgres → Supabase** (source-of-truth store).

You do the one-time account/dashboard setup below once. After that it's hands-off:
push → merge → live.

---

## One-time setup

Accounts needed: [Supabase](https://supabase.com), [Render](https://render.com),
[Vercel](https://vercel.com) — all have free tiers. Do the steps **in order**
(the order resolves the API-URL ↔ console-URL chicken-and-egg).

### 1. Supabase Postgres

1. Create a new Supabase project; pick a strong DB password.
2. Project → **Connect** (or Settings → Database) → copy the connection details.
   You need the **host**, **port** (`5432`), **database** (`postgres`), **user**,
   and **password**. Build the JDBC URL:
   ```
   APP_POSTGRES_URL=jdbc:postgresql://db.<your-ref>.supabase.co:5432/postgres
   APP_POSTGRES_USER=postgres
   APP_POSTGRES_PASSWORD=<the password you set>
   ```
   > If you use the pooled connection (port `6543`), use that port instead. Flyway
   > runs migrations on boot, so the user must own the `public` schema (the
   > default `postgres` user does).

### 2. Render — the Java API

Easiest path is the blueprint:

1. Render Dashboard → **New** → **Blueprint** → connect the GitHub repo
   `malwal86/adaptive-antispam-system`. Render reads [`render.yaml`](render.yaml)
   and proposes the `living-antispam` web service.
2. When prompted, set the env vars marked `sync: false`:
   - `APP_POSTGRES_URL`, `APP_POSTGRES_USER`, `APP_POSTGRES_PASSWORD` — from step 1.
   - `ANTISPAM_CONSOLE_ALLOWED_ORIGINS` — leave as `https://*.vercel.app` for now
     (you'll have an exact URL after step 3; the wildcard already covers Vercel).
   - `APP_REDIS_URL` / `APP_KAFKA_BOOTSTRAP_SERVERS` already have placeholder
     values in `render.yaml` — leave them; they aren't connected yet.
3. Apply. Wait for the first build to go green and `/health` to report `UP`.
4. Note the service URL, e.g. `https://living-antispam.onrender.com`.
5. Settings → **Deploy Hook** → copy the hook URL (used by CI to trigger deploys).

> `autoDeploy` is **off** by design — CI is the deploy trigger, so a red build
> never ships. The deploy hook is how CI tells Render to build.

### 3. Vercel — the console

1. Vercel → **Add New… → Project** → import the same GitHub repo.
2. **Root Directory** → set to `console` (the Next.js app lives in a subdirectory).
   Framework auto-detects as **Next.js**; leave build/output defaults.
3. Environment Variables → add:
   ```
   NEXT_PUBLIC_API_BASE_URL = https://living-antispam.onrender.com   (your step-2 URL)
   ```
   Add it for **Production, Preview, and Development** so preview deploys also hit
   the live API. (`NEXT_PUBLIC_*` is inlined at build time, so it must be set
   before the build.)
4. Deploy. Note the production URL, e.g. `https://adaptive-antispam-system.vercel.app`.

### 4. Tighten CORS (optional but recommended)

The `https://*.vercel.app` wildcard already lets the production **and** preview
console call the API. If you later add a custom domain, append it:

- Render → `living-antispam` → Environment → edit `ANTISPAM_CONSOLE_ALLOWED_ORIGINS`:
  ```
  https://*.vercel.app,https://your-custom-domain.com
  ```
- Save → Render redeploys the API.

### 5. GitHub — CI deploy + live smoke test

Give CI the deploy hook and the live URL. From a shell with `gh` authenticated:

```bash
# Secret: the Render deploy hook from step 2.5
gh secret set RENDER_DEPLOY_HOOK_URL --repo malwal86/adaptive-antispam-system \
  --body 'https://api.render.com/deploy/srv-xxxxxxxx?key=yyyyyyyy'

# Variable: the live API base URL from step 2.4 (public, so a variable not a secret)
gh variable set LIVE_API_URL --repo malwal86/adaptive-antispam-system \
  --body 'https://living-antispam.onrender.com'
```

(Or set them in GitHub → repo → Settings → Secrets and variables → Actions.)

If `RENDER_DEPLOY_HOOK_URL` is unset, CI skips the deploy step (no failure); if
`LIVE_API_URL` is unset, it skips the smoke test.

---

## The ongoing flow (after setup)

1. Work on a branch, open a PR. CI runs the **full** suite (Java unit +
   integration with Testcontainers, console unit + Playwright E2E) on the PR.
   Vercel posts a **preview** URL on the PR — the real UI against the live API.
2. Merge to `main`. On the green `main` build, CI:
   - triggers the Render API deploy,
   - polls `/info` until the new commit is the one serving,
   - asserts `/health` is `UP` and a denylisted-URL email returns `tier=block`.
   Vercel independently promotes the console to **production**.
3. Both URLs now serve the new build. The Actions run is green only if the live
   API actually came up and decided correctly.

## Verifying it works

```bash
# API health + a live decision
curl -s https://living-antispam.onrender.com/health
curl -s https://living-antispam.onrender.com/info       # shows version + commit
curl -s -X POST https://living-antispam.onrender.com/analyze \
  -H 'Content-Type: application/json' \
  -d '{"raw":"From: x@promo.example\nSubject: hi\n\nhttp://malware.example/login\n"}'
# → {"tier":"block","routeUsed":"hard_rule",...}

# Console: open the Vercel URL, paste an email or pick a sample, watch the card.
```

## Notes & gotchas

- **Cold starts:** Render's free/starter plan sleeps idle services; the first
  request after idle (and the CI smoke poll) can take ~30–60 s. The smoke test
  allows ~15 min for a fresh build to go live.
- **Redis/Kafka** are placeholders (`redis://placeholder:6379`, `placeholder:9092`)
  — the app only validates they're *present*, it doesn't connect yet. Replace them
  when Epics 02/03 land.
- **The smoke test writes one row** to the live DB per deploy (`source=ci-smoke`).
  Ingest is idempotent (same bytes dedupe), so emails don't pile up; only a
  `classifications` row is added each run. Harmless for a demo.
- **Secrets never live in the repo** — only env vars in the Render/Vercel
  dashboards and GitHub Actions secrets.
- **Preview CORS:** previews work live because the API allows `https://*.vercel.app`.
  If you scope CORS to an exact production origin only, preview deploys won't be
  able to call the API (the UI loads, requests fail).
