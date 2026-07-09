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

// A single uncertain email decided twice (story 05.06): first provisionally withheld as
// quarantine-pending on the LLM route, then resolved to allow by the async LLM. Same emailId,
// different classificationId — the console must show one card flipping, never two.
const PENDING = {
  ...BLOCK,
  emailId: "9f1c0e7a-5b2d-4a6f-9c3e-1d8b7a4f2c10",
  classificationId: "p1",
  tier: "quarantine",
  reasonCodes: [],
  routeUsed: "llm",
  explanation: "Uncertain — provisionally withheld pending LLM resolution.",
};

const RESOLVED = {
  ...PENDING,
  classificationId: "p2",
  tier: "allow",
  routeUsed: "llm",
  explanation: "LLM judged the message legitimate; promoted to the inbox.",
};

function streamBody(): string {
  return (
    `id: 1\nevent: decision\ndata: ${JSON.stringify(BLOCK)}\n\n` +
    `id: 2\nevent: decision\ndata: ${JSON.stringify(ALLOW)}\n\n`
  );
}

function pendingThenResolvedBody(): string {
  return (
    `id: 1\nevent: decision\ndata: ${JSON.stringify(PENDING)}\n\n` +
    `id: 2\nevent: decision\ndata: ${JSON.stringify(RESOLVED)}\n\n`
  );
}

// A warm-up → attack sequence for the story panel (story 12.04). Two good model decisions earn
// trust (low posterior), then three LLM-routed attack decisions drive the posterior up (trust
// collapses) and each charges real cost — enough to exhaust the 0.5 daily cap, halting the meter.
function attackBody(): string {
  const warmUp = [0.08, 0.12].map((posterior, i) => ({
    ...ALLOW,
    emailId: `warm-${i}`,
    classificationId: `w${i}`,
    tier: "allow",
    routeUsed: "model",
    posterior,
  }));
  const attack = [0.82, 0.88, 0.93].map((posterior, i) => ({
    ...BLOCK,
    emailId: `atk-${i}`,
    classificationId: `a${i}`,
    tier: "quarantine",
    reasonCodes: [],
    routeUsed: "llm",
    posterior,
    llmCostUsd: 0.2,
  }));
  return [...warmUp, ...attack]
    .map((d, i) => `id: ${i + 1}\nevent: decision\ndata: ${JSON.stringify(d)}\n\n`)
    .join("");
}

// One arena run where the fixed baseline let far more danger through (60%) than the current model
// (10%) — a real "danger missed by baseline" row (story 08.04).
const TREND = {
  points: [
    {
      runId: "abcdef12-3456-7890-abcd-ef1234567890",
      runAt: "2026-06-02T00:00:00Z",
      bypassRate: 0.1,
      baselineBypassRate: 0.6,
      precisionFpRate: null,
    },
  ],
  firstBypassRate: 0.1,
  latestBypassRate: 0.1,
  improved: false,
};

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

async function stubStream(page: Page, onRequest?: () => void, body: string = streamBody()) {
  await page.route(`${API}/decisions/stream`, (route) => {
    onRequest?.();
    return route.fulfill({
      contentType: "text/event-stream",
      headers: { "Cache-Control": "no-cache" },
      body,
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
  // The story panel reads the arena bypass trend for its baseline-miss table.
  await page.route(`${API}/arena/trend**`, (route) => route.fulfill({ json: TREND }));
}

// The thunderclap trigger (story 12.05): the runner accepts the start and injects asynchronously, so
// the endpoint returns 202 immediately while the beats arrive on the already-open decision stream.
async function stubScenario(page: Page, onStart?: () => void) {
  await page.route(`${API}/controls/scenarios/*/start`, (route) => {
    onStart?.();
    return route.fulfill({
      status: 202,
      json: { scenario: "sender_warms_up_then_attacks", steps: 18, seed: 42, shadowPolicyVersion: "thunderclap-shadow-1" },
    });
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

test("each card's route indicator and reason chips reflect how it was decided", async ({ page }) => {
  await stubStream(page);
  await stubControls(page);
  await page.goto("/");

  const cards = page.getByTestId("live-decision-card");
  await expect(cards).toHaveCount(2);

  // Newest-first: the model-routed allow leads, the hard-rule block follows.
  // (route-used also carries its Material Symbol glyph, so match on contained text.)
  await expect(cards.first().getByTestId("route-used")).toContainText("Model");
  const blockCard = cards.nth(1);
  await expect(blockCard.getByTestId("route-used")).toContainText("Hard rule");
  await expect(blockCard.getByTestId("reason-chip")).toContainText("Known-bad URL");
});

test("a quarantine-pending email resolves in place — one card, flipped, never retracted", async ({
  page,
}) => {
  await stubStream(page, undefined, pendingThenResolvedBody());
  await stubControls(page);
  await page.goto("/");

  // The pending row and its resolution share an email, so there is exactly one card,
  // and it ends on the resolved tier — the pending → final-tier flip (story 05.06).
  const card = page.getByTestId("live-decision-card");
  await expect(card).toHaveCount(1);
  await expect(card).toHaveAttribute("data-tier", "allow");
  await expect(card).toHaveAttribute("data-resolved", "true");
  await expect(card.getByTestId("pending-indicator")).toHaveCount(0);

  // The stub replays the full body on every EventSource reconnect; the fold is idempotent,
  // so the card never flickers back to quarantine — no deliver-then-retract.
  await page.waitForTimeout(1500);
  await expect(card).toHaveCount(1);
  await expect(card).toHaveAttribute("data-tier", "allow");
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

test("the story panel reflects the attack: trust collapses, routing shifts to LLM, cost halts at the cap, baseline table populated", async ({
  page,
}) => {
  await stubStream(page, undefined, attackBody());
  await stubControls(page);
  await page.goto("/");

  // Reputation: the curve renders and flags the collapse the attack caused (high posterior → low trust).
  const chart = page.getByTestId("reputation-chart");
  await expect(chart).toBeVisible();
  await expect(chart).toHaveAttribute("data-collapsing", "true");
  await expect(page.getByTestId("reputation-collapse")).toBeVisible();

  // Route mix ("How it decided"): the real rules/reputation/LLM split — two model warm-ups, three
  // LLM attack decisions.
  await expect(page.getByTestId("route-count-reputation")).toHaveText("2");
  await expect(page.getByTestId("route-count-llm")).toHaveText("3");

  // The cost and benchmark read-outs live under a "For the curious" disclosure — expand it first.
  await page.getByText("For the curious", { exact: false }).click();

  // Cost meter: real summed spend (3 × $0.20) exhausts the $0.50 cap and the meter visibly halts.
  await expect(page.getByTestId("cost-spent")).toHaveText("$0.60");
  await expect(page.getByTestId("cost-meter")).toHaveAttribute("data-cost-at-cap", "true");
  await expect(page.getByTestId("cost-cap-hit")).toBeVisible();

  // Baseline-miss table: the run where the baseline let danger through that the current model caught.
  const rows = page.getByTestId("baseline-miss-row");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText("base 60%");
  await expect(rows.first()).toContainText("now 10%");
});

test("running the thunderclap from the rail triggers the runner and the panels animate its beats", async ({
  page,
}) => {
  let started = 0;
  // The stream carries the warm-up → attack sequence the runner would drive; the button click is what
  // invokes that runner (here stubbed), proving the single-control-action wiring end to end.
  await stubStream(page, undefined, attackBody());
  await stubControls(page);
  await stubScenario(page, () => {
    started += 1;
  });
  await page.goto("/");

  // One control action: run the selected scenario → the runner endpoint is invoked and acknowledged.
  await page.getByTestId("scenario-start").click();
  await expect.poll(() => started).toBe(1);
  await expect(
    page.getByTestId("scenario-control").getByTestId("apply-status"),
  ).toHaveAttribute("data-status", "applied");

  // The story panel animates the beats the run drives on the live stream: reputation collapse,
  // routing shifting to the LLM, and the cost meter halting at the cap.
  await expect(page.getByTestId("reputation-chart")).toHaveAttribute("data-collapsing", "true");
  await expect(page.getByTestId("reputation-collapse")).toBeVisible();
  await expect(page.getByTestId("route-count-llm")).toHaveText("3");
  await expect(page.getByTestId("cost-meter")).toHaveAttribute("data-cost-at-cap", "true");
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
