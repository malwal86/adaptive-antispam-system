import { defineConfig, devices } from "@playwright/test";

const PORT = 3100;
const BASE_URL = `http://localhost:${PORT}`;

/**
 * E2E runs against the real Next.js app, with the Java API stubbed via route
 * interception (see e2e/analyzer.spec.ts) so the test is hermetic — it exercises
 * the full UI flow (paste/pick → fetch → animated render) without needing the
 * backend running. The API contract itself is covered by the Java AnalyzeApiTest.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: "list",
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    // Production build + start: closest to what ships, and stable for CI.
    command: `npm run build && npm run start -- --port ${PORT}`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    env: { NEXT_PUBLIC_API_BASE_URL: "http://api.test" },
  },
});
