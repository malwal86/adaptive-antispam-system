// Typed client over the Java API. The console is a *thin* client: it never
// reimplements decision logic — it submits an email and renders what the API
// decided. Base URL is configured per environment (CORS-allowed origin on the
// Java side); defaults to the local Spring dev server.

export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://localhost:8080";

export type Tier = "allow" | "warn" | "quarantine" | "block";

/** Response of POST /analyze and GET /analyze/{emailId} (see AnalyzeResponse.java). */
export interface AnalyzeResponse {
  emailId: string;
  classificationId: string;
  tier: Tier;
  reasonCodes: string[];
  routeUsed: string;
  latencyMs: number;
  explanation: string;
  decidedAt: string;
  duplicate: boolean;
}

/** Item of GET /seed/samples (see SeedSampleResponse.java). */
export interface SeedSample {
  emailId: string;
  label: "ham" | "spam" | "phish";
  dataset: string;
  subject: string | null;
  senderDomain: string | null;
}

async function postAnalyze(body: Record<string, unknown>): Promise<AnalyzeResponse> {
  const res = await fetch(`${API_BASE_URL}/analyze`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(await errorMessage(res));
  }
  return (await res.json()) as AnalyzeResponse;
}

/** Analyze a pasted raw email. */
export function analyzeRaw(raw: string, source = "console"): Promise<AnalyzeResponse> {
  return postAnalyze({ raw, source });
}

/** Analyze an already-ingested email (a picked seed sample) by id — no PII on the wire. */
export function analyzeById(emailId: string): Promise<AnalyzeResponse> {
  return postAnalyze({ emailId });
}

/** Labeled seed samples for the picker. */
export async function fetchSamples(perLabel = 4): Promise<SeedSample[]> {
  const res = await fetch(`${API_BASE_URL}/seed/samples?perLabel=${perLabel}`);
  if (!res.ok) {
    throw new Error(await errorMessage(res));
  }
  return (await res.json()) as SeedSample[];
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: string };
    if (body?.error) {
      return body.error;
    }
  } catch {
    // fall through to status text
  }
  return `Request failed (${res.status} ${res.statusText})`;
}
