# ML: bootstrap classifier (story 04.01)

"Train where it's easy, serve where it's fast" — the spam/phishing classifier is
trained here in Python and exported to ONNX, then served in-process by the Java app
via ONNX Runtime (`com.antispam.decision.model.OnnxModel`). No model-server hop on
the synchronous decision path.

This is the **bootstrap** model: a 3-class (`ham`/`spam`/`phish`) logistic-regression
pipeline fit on synthetic, class-conditioned feature vectors with a fixed seed. It
exists so the serving path and the feature-vector contract are real and testable
today. The real retrain loop (Epic 10) replaces the artifact with one trained on
exported corpus + arena features; the serving code does not change.

## Files

- `feature_schema.py` — the canonical 24-feature input order + auth encoding. The
  cross-language contract; must match `ModelFeatureVector.java` exactly.
- `train_classifier.py` — generates data, trains, exports ONNX, writes the parity fixture.
- `requirements.txt` — pinned toolchain that produced the checked-in artifacts.

## Outputs (checked in)

- `../src/main/resources/models/spam-classifier-bootstrap-v1.onnx` — served by Java.
- `../src/test/resources/models/parity-cases.json` — Java↔Python export-fidelity fixture.

## Regenerate

```bash
python3 -m venv .venv
.venv/bin/pip install -r ml/requirements.txt
.venv/bin/python ml/train_classifier.py
```

The run is deterministic (fixed `SEED`), so it reproduces the same `.onnx` and
fixture. If you change the feature layout, bump it in `feature_schema.py` **and**
`ModelFeatureVector.java` together (a feature-version change), and bump
`MODEL_VERSION` in both `train_classifier.py` and `OnnxModel.java` (and the `.onnx`
filename). The Java parity and vector tests will fail loudly if the two sides drift.
