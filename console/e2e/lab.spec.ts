import { test, expect, type Page } from "@playwright/test";

// The lab console subscribes to the Java SSE feed. As with the analyzer e2e, the
// backend is stubbed via route interception so the test is hermetic — here the
// stub speaks `text/event-stream`, exercising the real EventSource transport,
// reconnection, and de-duplication without a running backend.

const API = "http://api.test";

const BLOCK = {
  emailId: "d7db7c1d-59aa-4e88-83a3-895c13b39907",
  classificationId: "c1",
  tier: "block",
  reasonCodes: ["KNOWN_BAD_URL"],
  routeUsed: "hard_rule",
  latencyMs: 2,
  explanation: "Blocked: a link resolves to a known-malicious host.",
  decidedAt: "2026-06-05T12:00:00Z",
  duplicate: false,
};

const ALLOW = {
  ...BLOCK,
  emailId: "41c66230-2c39-4c97-8850-9eee0132de52",
  classificationId: "c2",
  tier: "allow",
  reasonCodes: [],
  routeUsed: "model",
  explanation: "No hard-rule or model signal fired; allowed.",
};

function streamBody(): string {
  return (
    `id: 1\nevent: decision\ndata: ${JSON.stringify(BLOCK)}\n\n` +
    `id: 2\nevent: decision\ndata: ${JSON.stringify(ALLOW)}\n\n`
  );
}

const POLICY = {
  version: "bootstrap-v1",
  active: true,
  warnThreshold: 0.3,
  quarantineThreshold: 0.6,
  blockThreshold: 0.85,
  llmThreshold: 0.5,
  routingBandWidth: 0.05,
  burstThreshold: 5,
  modelVersion: "model-v1",
  createdAt: "2026-06-01T00:00:00Z",
};

async function stubStream(page: Page, onRequest?: () => void) {
  await page.route(`${API}/decisions/stream`, (route) => {
    onRequest?.();
    return route.fulfill({
      contentType: "text/event-stream",
      headers: { "Cache-Control": "no-cache" },
      body: streamBody(),
    });
  });
}

async function stubControls(page: Page, onThresholds?: (body: unknown) => void) {
  await page.route(`${API}/controls/policies`, (route) => route.fulfill({ json: [POLICY] }));
  await page.route(`${API}/controls/budget`, (route) =>
    route.fulfill({ json: { enabled: false, dailyCapUsd: 0.5, monthlyCapUsd: 5 } }),
  );
  await page.route(`${API}/controls/thresholds`, (route) => {
    onThresholds?.(route.request().postDataJSON());
    return route.fulfill({ json: { ...POLICY, version: "console-1", warnThreshold: 0.1 } });
  });
}

test("renders three panes and streams decisions in live, newest-first", async ({ page }) => {
  await stubStream(page);
  await stubControls(page);
  await page.goto("/");

  await expect(page.getByTestId("controls-rail")).toBeVisible();
  await expect(page.getByTestId("center-stream")).toBeVisible();
  await expect(page.getByTestId("right-rail")).toBeVisible();
  await expect(page.getByTestId("stream-status")).toBeVisible();

  const cards = page.getByTestId("live-decision-card");
  await expect(cards).toHaveCount(2);
  // The last event received leads the newest-first list.
  await expect(cards.first()).toHaveAttribute("data-tier", "allow");
  await expect(cards.nth(1)).toHaveAttribute("data-tier", "block");
});

test("a threshold change posts new thresholds and is acknowledged", async ({ page }) => {
  let posted: unknown = null;
  await stubStream(page);
  await stubControls(page, (body) => {
    posted = body;
  });
  await page.goto("/");

  // Lower the warn threshold, then apply — a real reconfiguration call, acknowledged in the UI.
  await page.getByTestId("warn-slider").fill("0.1");
  await page.getByTestId("apply-thresholds").click();

  await expect(page.getByTestId("apply-status").first()).toHaveAttribute("data-status", "applied");
  expect(posted).toMatchObject({ warnThreshold: 0.1 });
});

test("auto-reconnects when the connection drops, without duplicating cards", async ({ page }) => {
  let requests = 0;
  await stubStream(page, () => {
    requests += 1;
  });
  await stubControls(page);
  await page.goto("/");

  await expect(page.getByTestId("live-decision-card")).toHaveCount(2);

  // The finite stubbed response closes the stream; EventSource reconnects on its
  // own (re-requesting, replaying id 1–2). The dedupe-by-classificationId keeps
  // the card count stable — no duplicates, nothing lost.
  await expect.poll(() => requests, { timeout: 15_000 }).toBeGreaterThan(1);
  await expect(page.getByTestId("live-decision-card")).toHaveCount(2);
});
