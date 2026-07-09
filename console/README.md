# Living Spam Classifier Lab Console

The single-email analyzer (story **01.05**) and the seed of the **Living Spam Classifier Lab
Console** (Epic 12). A thin Next.js client over the Java API: paste or pick an
email and see the system's decision — tier (colour-coded), reason chips, route,
latency, and a grounded explanation — as an animated result card.

It is deliberately a *thin* client: it submits an email and renders what the API
decided. No decision logic lives here.

## Stack

- **Next.js 15** (App Router) + **React 19** + **TypeScript**
- **Tailwind CSS** with Material Design 3 tokens; **Roboto** + **Material Symbols**
  (per `planning/animation-and-ui-guidelines.md`)
- **framer-motion** for the animated, reduced-motion-aware tier transitions
- shadcn/ui-style primitives (`components/ui/*`)
- **Vitest** + Testing Library (components) and **Playwright** (E2E)

## Run locally

The console talks to the Java API (default `http://localhost:8080`). Bring the API
up first (see the repo README), then:

```bash
npm install
cp .env.example .env.local        # adjust NEXT_PUBLIC_API_BASE_URL if needed
npm run dev                        # http://localhost:3000
```

The API must CORS-allow the console origin — `http://localhost:3000` is allowed by
default (`antispam.console.allowed-origins` on the Spring side).

## Test

```bash
npm test            # Vitest component/unit tests
npm run e2e         # Playwright E2E (Java API stubbed via route interception)
```

`npm run e2e` is hermetic — it stubs the API so it exercises the full UI flow
(paste/pick → fetch → animated render) without the backend running. The API
contract itself is covered by the Java `AnalyzeApiTest`.

## Configuration

| Env var | Purpose | Default |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | Base URL of the Java API | `http://localhost:8080` |

`NEXT_PUBLIC_*` values are **inlined at build time**, so in Docker/Render the API
URL is passed as a build arg (see `Dockerfile` / `render.yaml`).

## Layout

```
app/
  layout.tsx        Roboto + Material Symbols, M3 theme
  page.tsx          Living Spam Classifier Lab header + tier legend + <Analyzer/>
  globals.css       Tailwind + M3 tonal tokens, reduced-motion
components/
  Analyzer.tsx      paste box + sample picker + submit + result/empty/error
  ResultCard.tsx    animated 4-tier verdict card
  SamplePicker.tsx  labeled seed-sample chips
  ReasonChip.tsx    reason-code chip
  ui/               shadcn-style primitives (button, card, badge, textarea, icon)
lib/
  api.ts            typed client for /analyze + /seed/samples
  tiers.ts          tier + reason-code presentation metadata
__tests__/          Vitest component tests
e2e/                Playwright specs
```
