# Data Protection Note

How the Living Anti-Spam System handles personal data in email. This note records the
lawful basis, the data categories, retention, and the standards the design leans on,
and documents the mechanisms that enforce the posture. It is built up across Epic 14
(Privacy & Data Protection); this revision covers stories 14.01–14.02.

## Posture

**Protect at rest, redact at egress, crypto-shred for erasure.** The canonical raw
store is never redacted — it must stay byte-faithful for replay, eval, and retrain.
Redaction and minimization happen only on egress; erasure is achieved by destroying
keys, which leaves the immutable record intact but its content unrecoverable.

## Lawful basis

Anti-spam / anti-abuse is a recognized **legitimate interest** (GDPR Recital 49). The
*purpose* is defensible, but data-minimization and storage-limitation still apply, which
is why every egress surface (API, logs, LLM, export, console) is minimized or masked, and
why an erasure path exists despite the canonical record being immutable.

## Data categories

| Category | Where | Treatment |
|---|---|---|
| Raw message bytes (body) | `emails.raw_content` | Encrypted at rest (14.02); never exposed on default reads; revealed only via privileged, access-controlled accessors (14.05). |
| Email addresses (sender / recipients) | `emails.sender`, `emails.recipients` | Masked on default reads and in logs, local-part hidden, domain kept (14.01). |
| Parsed header metadata (subject, auth results, timestamps) | `emails.*` columns | Stored for classification; subject/recipients masked at egress. |
| Content hash | `emails.content_hash` | SHA-256 of plaintext; non-PII; the idempotency key. |

## Retention

The canonical `emails` row is immutable and retained for replay/eval/retrain. Storage
limitation is honored not by deleting rows (which immutability forbids) but by
**crypto-shredding** the body on an erasure request — see below.

## Encryption at rest (story 14.02)

Email bodies are stored as ciphertext using **envelope encryption**:

- Each body is encrypted under a fresh 256-bit **data key (DEK)** with AES-256-GCM.
- The DEK is itself encrypted ("wrapped") under a **master key**, also AES-GCM, and only
  the wrapped DEK is persisted — in a separate, mutable table, `email_content_keys`.
- The content hash is computed over the **plaintext** before encryption, so idempotency
  is unaffected by the random IV/DEK that makes each ciphertext differ.
- Decryption is transparent at the repository read path: every caller sees byte-faithful
  plaintext and never knows encryption happened.

Master keys are base64-encoded AES keys supplied **only** through the environment / secret
manager, never the repo:

```
ANTISPAM_ENCRYPTION_ACTIVE_KEY_VERSION=v1
ANTISPAM_ENCRYPTION_KEYS_V1=<base64 32-byte key>
```

With no key configured the cipher is disabled and bodies are stored as plaintext, so local
dev, the test profile, and a keyless deploy boot unchanged. Encryption engages once a key
and active version are set.

## Erasure by crypto-shredding (GDPR Art. 17)

The canonical record is immutable (`emails`, story 01.02), which collides with the right
to erasure. We resolve the tension without weakening immutability:

- `POST /emails/{id}/erasure` destroys the per-record DEK in `email_content_keys`
  (sets `wrapped_dek = null`, stamps `erased_at`).
- The `emails` row is **never** updated or deleted — the immutability trigger still holds.
- The body ciphertext remains but is now permanently unrecoverable; reads report a
  **content-erased** state (the reveal view returns `contentErased: true` and no body; the
  raw accessor returns `410 Gone`).
- Destroying keys to de-identify data is a recognized technique (ISO/IEC 20889).

Erasure covers the body. Address/subject metadata is minimized at egress (masked by
default, 14.01) rather than crypto-shredded, since those columns live in the immutable row
and are also load-bearing lookup keys (e.g. sender reputation).

> The erasure and reveal/raw accessors are **privileged**; server-side authorization for
> them lands in story 14.05.

## Master-key rotation

Rotation is additive and keeps every record readable throughout:

1. Add a new master key version (`ANTISPAM_ENCRYPTION_KEYS_V2`) and set
   `ANTISPAM_ENCRYPTION_ACTIVE_KEY_VERSION=v2`, keeping `v1` available.
2. `KeyRotationService.rotate()` re-wraps every live DEK under the active key — only the
   small wrapped keys change; the immutable content ciphertext is never rewritten.
3. Once every record is on `v2`, the old `v1` master key can be retired.

Erased records are skipped (their key is already gone), and re-running rotation is a no-op
once converged.

## PII masking before LLM egress (story 14.03)

The LLM fallback (Subsystem 5) sends message content to a third-party model — the single
largest PII-egress event in the pipeline. Before the prompt leaves our boundary:

- The **grounded context** (the trusted half of the prompt) carries *no* raw content — only
  derived numeric features, a reputation summary, and the escalation reasons.
- The **untrusted email block** (sender, subject, body) is scrubbed by `PiiMasker`:
  email addresses (local-part hidden, domain kept), phone numbers (`[phone]`), and obvious
  card/account numbers (`[card-number]`) are masked, while URLs, brand mentions, and urgency
  language — the features the classifier keys on — are preserved verbatim. Masking runs
  *before* the structural injection-defense defang (05.05).

Masking is **idempotent** and **configurable per provider** via
`ANTISPAM_LLM_PII_MASKING_LEVEL` (`STRICT` by default; `OFF` only for a provider whose DPA
makes masking unnecessary). No raw prompt/PII is logged or persisted — the system records
the verdict, probabilities, reason codes, and cost, never the prompt text.

### Provider data-handling

The default provider is configured via Spring AI (`spring.ai.openai.*`), model
`gpt-4o-mini`-class. The provider is pluggable (one `ChatClient` adapter,
`SpringAiLlmChatPort`). When enabling a provider for a deployment, confirm and record its
data-handling terms — **no-training on submitted data** and **retention window** — and pick
the masking level accordingly. Masking is the technical control; the DPA is the contractual
one, and both apply.

## Standards referenced

- **GDPR** Recital 49 (anti-spam as legitimate interest), Art. 17 (right to erasure).
- **ISO/IEC 20889** — de-identification technique taxonomy (key destruction).
- **NIST SP 800-122 / 800-188** — PII protection and de-identification.
- **HIPAA Safe Harbor** — concrete identifier list (addresses, names, etc.).
- **ISO/IEC 27018** — protection of PII in public clouds (managed Supabase / Upstash / Aiven).
