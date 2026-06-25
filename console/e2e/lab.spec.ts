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

test("renders three panes and streams decisions in live, newest-first", async ({ page }) => {
  await stubStream(page);
  await page.goto("/");

  await expect(page.getByTestId("left-rail")).toBeVisible();
  await expect(page.getByTestId("center-stream")).toBeVisible();
  await expect(page.getByTestId("right-rail")).toBeVisible();
  await expect(page.getByTestId("stream-status")).toBeVisible();

  const cards = page.getByTestId("live-decision-card");
  await expect(cards).toHaveCount(2);
  // The last event received leads the newest-first list.
  await expect(cards.first()).toHaveAttribute("data-tier", "allow");
  await expect(cards.nth(1)).toHaveAttribute("data-tier", "block");
});

test("auto-reconnects when the connection drops, without duplicating cards", async ({ page }) => {
  let requests = 0;
  await stubStream(page, () => {
    requests += 1;
  });
  await page.goto("/");

  await expect(page.getByTestId("live-decision-card")).toHaveCount(2);

  // The finite stubbed response closes the stream; EventSource reconnects on its
  // own (re-requesting, replaying id 1–2). The dedupe-by-classificationId keeps
  // the card count stable — no duplicates, nothing lost.
  await expect.poll(() => requests, { timeout: 15_000 }).toBeGreaterThan(1);
  await expect(page.getByTestId("live-decision-card")).toHaveCount(2);
});
