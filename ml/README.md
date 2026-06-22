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

## Outputs (checked in)

- `../src/main/resources/models/spam-classifier-bootstrap-v1.onnx` — classifier, served by Java.
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
