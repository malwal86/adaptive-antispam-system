import { test, expect, type Page } from "@playwright/test";

const API = "http://api.test";

const BLOCK_DECISION = {
  emailId: "d7db7c1d-59aa-4e88-83a3-895c13b39907",
  classificationId: "b5c0b6eb-64d5-4932-97a0-33fd65576adf",
  tier: "block",
  reasonCodes: ["KNOWN_BAD_URL"],
  routeUsed: "hard_rule",
  latencyMs: 2,
  explanation: "Blocked: a link resolves to a known-malicious host.",
  decidedAt: "2026-06-05T12:00:00Z",
  duplicate: false,
};

const QUARANTINE_DECISION = {
  ...BLOCK_DECISION,
  emailId: "41c66230-2c39-4c97-8850-9eee0132de52",
  classificationId: "aa0f6f55-fefa-47da-b105-9e8027009473",
  tier: "quarantine",
  reasonCodes: ["MALFORMED_AUTH_BRAND_SPOOF"],
  explanation: "Quarantined: impersonates a high-value brand but fails authentication (DMARC).",
  duplicate: true,
};

const SAMPLES = [
  {
    emailId: "41c66230-2c39-4c97-8850-9eee0132de52",
    label: "phish",
    dataset: "phishtank",
    subject: "Your account is limited",
    senderDomain: "paypal.account-verify.com",
  },
];

async function stubApi(page: Page) {
  await page.route(`${API}/seed/samples**`, (route) =>
    route.fulfill({ json: SAMPLES }),
  );
  await page.route(`${API}/analyze`, (route) => {
    const body = route.request().postDataJSON() as { emailId?: string; raw?: string };
    const json = body.emailId ? QUARANTINE_DECISION : BLOCK_DECISION;
    return route.fulfill({ json });
  });
}

test("paste an email, submit, and see the tier badge and reason chip", async ({ page }) => {
  await stubApi(page);
  await page.goto("/");

  await expect(page.getByTestId("empty-state")).toBeVisible();

  await page.getByTestId("raw-input").fill(
    "From: deals@promo.example\nSubject: Act now\n\nVerify at http://malware.example/login\n",
  );
  await page.getByTestId("analyze-button").click();

  const card = page.getByTestId("result-card");
  await expect(card).toBeVisible();
  await expect(card).toHaveAttribute("data-tier", "block");
  await expect(page.getByTestId("tier-label")).toHaveText("Block");
  await expect(page.getByTestId("route-used")).toHaveText("Hard rule");
  await expect(page.getByTestId("reason-chip")).toContainText("Known-bad URL");
});

test("pick a labeled seed sample and a decision renders", async ({ page }) => {
  await stubApi(page);
  await page.goto("/");

  await page.getByTestId("sample-chip").first().click();

  const card = page.getByTestId("result-card");
  await expect(card).toBeVisible();
  await expect(card).toHaveAttribute("data-tier", "quarantine");
  await expect(page.getByTestId("reason-chip")).toContainText("Brand spoof");
});
