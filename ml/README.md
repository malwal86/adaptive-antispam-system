# ML: bootstrap classifier (04.01) + local embedder (04.03)

"Train where it's easy, serve where it's fast" — the spam/phishing classifier is
trained here in Python and exported to ONNX, then served in-process by the Java app
via ONNX Runtime (`com.antispam.decision.model.OnnxModel`). No model-server hop on
the synchronous decision path. Story 04.03 adds a second artifact — a local
sentence embedder — served on the *same* ONNX Runtime
(`com.antispam.decision.embedding.OnnxEmbeddingModel`) at zero per-embedding API cost.

This is the **bootstrap** model: a 3-class (`ham`/`spam`/`phish`) logistic-regression
pipeline fit on synthetic, class-conditioned feature vectors with a fixed seed. It
exists so the serving path and the feature-vector contract are real and testable
today. The real retrain loop (Epic 10) replaces the artifact with one trained on
exported corpus + arena features; the serving code does not change.

## Files

- `feature_schema.py` — the canonical 24-feature input order + auth encoding. The
  classifier's cross-language contract; must match `ModelFeatureVector.java` exactly.
- `train_classifier.py` — generates data, trains, exports ONNX, writes the parity fixture.
- `embedding_schema.py` — the canonical text→hashed-vector tokenization + hashing
  for the embedder. The embedder's cross-language contract; must match
  `com.antispam.decision.embedding.TextHasher` exactly.
- `train_embedding.py` — generates a synthetic multi-topic corpus, fits an LSA
  encoder (hashed n-grams → L2 norm → truncated SVD → L2 norm), exports ONNX, writes
  the embedding parity fixture.
- `requirements.txt` — pinned toolchain that produced the checked-in artifacts.

### Retrain pipeline (story 10.02 — scheduled CI train → ONNX → calibrate)

The **slow living loop**: a scheduled GitHub Action (`.github/workflows/retrain.yml`,
nightly + on-demand) trains a fresh **candidate** from the labeled-data export (story
10.01), exports it to ONNX, **calibrates** it, and stages a versioned artifact. It
produces a *candidate only* — promotion is gated separately (10.03 precision floor, 10.04
flag flip), so nothing here touches the serving path.

- `fetch_training_data.py` — joins the labeled export (`GET /retrain/export`, labels) to
  per-email feature vectors (`GET /emails/{id}/features`), flattening each `FeatureSet`
  via `feature_schema.flatten_feature_set` into the trainable export. Examples whose
  features are missing at the export's `feature_version` are dropped.
- `feature_schema.flatten_feature_set` — the Python side of the flattening contract;
  mirrors `ModelFeatureVector.toVector` exactly (same order, sentinels, auth encoding).
- `train_candidate.py` — validates the export (loud failure → **no** artifact), trains a
  weighted logistic pipeline on a stratified train split, exports ONNX (self-checked
  against sklearn through ONNX Runtime — the same engine Java serves with), fits **isotonic
  calibration** on the held-out split, and writes the candidate + metadata
  (`model_version`, `feature_version`, `π_train`, provenance counts) + calibration report.
  The `model_version` is a deterministic hash of the training corpus, so the candidate is
  reproducible.
- `sample_corpus.py` — generates `fixtures/sample-training-export.json`, the checked-in
  fallback corpus the workflow trains on when no live API is configured.

Outputs are written to a **staging directory** (never `src/main/resources`): per candidate,
`spam-classifier-<version>.onnx`, `…​.metadata.json`, `…​.calibration.json`, and
`parity-cases.json`. The workflow uploads them as a GitHub artifact and (when Supabase
credentials are present) stages them to Supabase Storage under `candidates/<version>/`.

Run the pipeline locally against the sample corpus:

```bash
.venv/bin/python ml/sample_corpus.py                       # (re)generate the fixture
.venv/bin/python ml/train_candidate.py \
  --export ml/fixtures/sample-training-export.json --out candidate/
.venv/bin/python -m pytest ml/tests -q                     # the pipeline's tests
```

## Outputs (checked in)

- `../src/main/resources/models/spam-classifier-bootstrap-v1.onnx` — classifier, served by Java.
- `../src/main/resources/models/spam-classifier-bootstrap-v1.metadata.json` — π_train (training base rate) for log-odds fusion (story 04.04), read by `ModelMetadata.java`.
- `../src/main/resources/models/embed-bootstrap-v1.onnx` — embedder, served by Java.
- `../src/test/resources/models/parity-cases.json` — classifier Java↔Python fixture.
- `../src/test/resources/models/embedding-parity-cases.json` — embedder Java↔Python fixture.

## Regenerate

```bash
python3 -m venv .venv
.venv/bin/pip install -r ml/requirements.txt
.venv/bin/python ml/train_classifier.py
```

```bash
.venv/bin/python ml/train_embedding.py
```

The runs are deterministic (fixed `SEED`), so they reproduce the same `.onnx` and
fixtures. If you change the feature layout, bump it in `feature_schema.py` **and**
`ModelFeatureVector.java` together (a feature-version change), and bump
`MODEL_VERSION` in both `train_classifier.py` and `OnnxModel.java` (and the `.onnx`
filename). Likewise, if you change the embedder's tokenization/hashing, change it in
`embedding_schema.py` **and** `TextHasher.java` together, and bump `EMBEDDING_VERSION`
in both `embedding_schema.py` and `OnnxEmbeddingModel.java` (and the `.onnx` filename).
The Java parity and vector tests will fail loudly if the two sides drift.
