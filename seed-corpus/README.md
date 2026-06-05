# Seed corpus

The labeled body of mail that drives features, training, replay, and the demo.
Layout the loader expects:

```
seed-corpus/<dataset>/<class>/<file>
```

- `<dataset>` — provenance, recorded as `ground_truth_labels.dataset_source`
  (e.g. `spamassassin`, `enron`, `phishtank`).
- `<class>` — the ground truth, mapped to a `ground_truth_labels.label`:
  `easy_ham`/`hard_ham`/`ham` → **ham**, `spam` → **spam**, `phish`/`phishing` → **phish**.
- `<file>` — a single RFC-822 message (`.eml`, or any extension) **or** an
  `.mbox` file holding many messages (split at load time).

Each file is loaded through the normal ingest path (`emails.ingest_source = 'seed'`)
and deduped by content hash, so re-seeding is idempotent.

## What's committed here vs. downloaded

The files checked in under this directory are **small, synthetic samples** — not
real corpus mail. They exist so `make seed` works offline and the loader's tests
have a deterministic fixture, **without** committing third-party data or anyone's
real email into the repo.

To load the **real** public corpora, run `make seed-download` (see
[`scripts/seed.sh`](../scripts/seed.sh)), which fetches the sources below into
`build/seed-corpus/`, normalizes them into the layout above, and loads them.

## Public datasets, licensing & provenance

| Dataset | Class(es) | Source | License / terms |
|---|---|---|---|
| **SpamAssassin public corpus** | ham, spam | <https://spamassassin.apache.org/old/publiccorpus/> | Apache SpamAssassin project; provided for spam-filter research. Redistribute per the corpus `readme.html`. |
| **Enron email dataset** | ham | <https://www.cs.cmu.edu/~enron/> | Public dataset (FERC investigation), curated by CMU/William Cohen. Real corporate mail — treat as PII; protected at rest and redacted at egress per Epic 14. |
| **Nazario phishing corpus** | phish | <https://monkey.org/~jose/phishing/> | Jose Nazario's phishing collection, distributed as mbox for research use. |
| **PhishTank** *(optional)* | phish | <https://phishtank.org/developer_info.php> | Community phishing feed; API key + attribution required. URLs, not full messages — used to enrich, not as primary mail. |

Notes:
- These sources name the seeds called out in the PRD (Enron, SpamAssassin, phish
  feeds). The arena (Epic 06/08) perturbs **real** spam/phish from these, so the
  corpus is load-bearing beyond the demo.
- Enron is genuine human email: handle under the project's privacy posture
  (encryption at rest, egress redaction). Do **not** commit raw Enron mail.
- Always re-check each source's current terms before redistributing downloaded data.
