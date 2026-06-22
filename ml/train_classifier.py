"""Train the bootstrap spam/phishing classifier and export it to ONNX.

"Train where it's easy, serve where it's fast" (PRD §Architecture): the model is
fit here in scikit-learn and exported to ONNX so the Java app can serve it
in-process via ONNX Runtime on the synchronous <100ms path — no model-server hop.

This is the *bootstrap* model that story 04.01 needs to stand the serving path up
end-to-end. It is trained on **synthetic, class-conditioned feature vectors**
(not real corpus emails) generated from a fixed seed, so the artifact is
reproducible and checked in. The real retrain loop (Epic 10) replaces this model
with one trained on exported corpus + arena features; the *serving* code and the
feature-vector contract built in 04.01 stay the same.

Outputs (regenerate by running this script — see ml/README.md):
  - src/main/resources/models/spam-classifier-bootstrap-v1.onnx   (served by Java)
  - src/test/resources/models/parity-cases.json                   (Java↔Python parity fixture)

Run:
  python -m venv .venv && .venv/bin/pip install -r ml/requirements.txt
  .venv/bin/python ml/train_classifier.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from feature_schema import (
    FEATURE_COUNT,
    LABEL_HAM,
    LABEL_PHISH,
    LABEL_SPAM,
)

# Must match OnnxModel.MODEL_VERSION on the Java side and the .onnx filename.
MODEL_VERSION = "bootstrap-v1"

REPO_ROOT = Path(__file__).resolve().parent.parent
MODEL_OUT = REPO_ROOT / "src/main/resources/models" / f"spam-classifier-{MODEL_VERSION}.onnx"
PARITY_OUT = REPO_ROOT / "src/test/resources/models" / "parity-cases.json"

# Fixed seed so the synthetic corpus — and therefore the trained model — is
# byte-reproducible across machines and CI.
SEED = 20260621
SAMPLES_PER_CLASS = 1500

# ONNX opset target: conservative enough that the checked-in Java ONNX Runtime
# (1.26.x) loads the graph regardless of the (newer) Python exporter's default.
TARGET_OPSET = 15


def _sample_ham(rng: np.random.Generator, n: int) -> np.ndarray:
    """Calm, well-formed personal/business mail: a real subject and sender,
    little shouting, few links, authentication generally passing, sent during
    plausible waking hours."""
    x = np.zeros((n, FEATURE_COUNT), dtype=np.float64)
    x[:, 0] = 1.0                                             # hasSubject
    x[:, 1] = rng.normal(45, 15, n).clip(3, 200)             # subjectLength
    x[:, 2] = rng.beta(2, 12, n)                              # subjectUppercaseRatio (low)
    x[:, 3] = rng.poisson(0.1, n)                             # subjectExclamationCount
    x[:, 4] = 1.0                                             # hasSender
    x[:, 5] = rng.poisson(1.5, n).clip(1, 20)               # recipientCount
    x[:, 6] = (rng.random(n) < 0.03).astype(float)           # replyToDiffers (rare)
    x[:, 7] = rng.poisson(1.0, n).clip(0, 30)               # urlCount (few)
    x[:, 8] = np.minimum(x[:, 7], rng.poisson(0.8, n))       # uniqueDomainCount
    x[:, 9] = 0.0                                             # hasIpUrl
    x[:, 10] = 0.0                                            # hasPunycodeDomain
    x[:, 11] = (x[:, 7] > 0) * rng.normal(60, 25, n).clip(0, 400)  # maxUrlLength
    x[:, 12] = rng.normal(900, 400, n).clip(20, 8000)       # charCount
    x[:, 13] = x[:, 12] / rng.normal(6.0, 0.6, n).clip(3, 12)  # wordCount
    x[:, 14] = rng.beta(2, 18, n)                            # uppercaseRatio (low)
    x[:, 15] = rng.poisson(0.5, n)                           # exclamationCount
    x[:, 16] = rng.normal(5.0, 0.8, n).clip(2, 12)          # avgWordLength
    x[:, 17] = 1.0                                            # hasDate
    x[:, 18] = rng.integers(7, 21, n)                        # hourOfDayUtc (daytime)
    x[:, 19] = rng.integers(1, 6, n)                         # dayOfWeek (weekdays)
    x[:, 20] = 0.0                                            # weekend
    x[:, 21] = rng.choice([1.0, 0.0], n, p=[0.9, 0.1])      # spf (mostly pass)
    x[:, 22] = rng.choice([1.0, 0.0], n, p=[0.85, 0.15])    # dkim
    x[:, 23] = rng.choice([1.0, 0.0], n, p=[0.85, 0.15])    # dmarc
    return x


def _sample_spam(rng: np.random.Generator, n: int) -> np.ndarray:
    """Bulk promotional/scam blasts: SHOUTY subjects, exclamation runs, many
    links, longer bodies, weaker authentication, odd send hours."""
    x = np.zeros((n, FEATURE_COUNT), dtype=np.float64)
    x[:, 0] = 1.0
    x[:, 1] = rng.normal(70, 25, n).clip(5, 250)            # longer, salesy subjects
    x[:, 2] = rng.beta(6, 4, n)                              # high subject uppercase
    x[:, 3] = rng.poisson(2.5, n)                           # many subject "!!!"
    x[:, 4] = 1.0
    x[:, 5] = rng.poisson(1.2, n).clip(1, 20)
    x[:, 6] = (rng.random(n) < 0.25).astype(float)
    x[:, 7] = rng.poisson(6.0, n).clip(0, 60)              # many URLs
    x[:, 8] = np.minimum(x[:, 7], rng.poisson(4.0, n))
    x[:, 9] = (rng.random(n) < 0.10).astype(float)
    x[:, 10] = (rng.random(n) < 0.05).astype(float)
    x[:, 11] = (x[:, 7] > 0) * rng.normal(140, 60, n).clip(0, 600)
    x[:, 12] = rng.normal(1600, 700, n).clip(50, 12000)
    x[:, 13] = x[:, 12] / rng.normal(5.5, 0.6, n).clip(3, 12)
    x[:, 14] = rng.beta(5, 6, n)                             # shouty body
    x[:, 15] = rng.poisson(5.0, n)
    x[:, 16] = rng.normal(5.2, 1.0, n).clip(2, 14)
    x[:, 17] = rng.choice([1.0, 0.0], n, p=[0.7, 0.3])
    x[:, 18] = np.where(x[:, 17] > 0, rng.integers(0, 24, n), -1.0)
    x[:, 19] = np.where(x[:, 17] > 0, rng.integers(1, 8, n), -1.0)
    x[:, 20] = (x[:, 19] >= 6).astype(float)
    x[:, 21] = rng.choice([1.0, 0.0, -0.5], n, p=[0.4, 0.4, 0.2])
    x[:, 22] = rng.choice([1.0, 0.0, -1.0], n, p=[0.35, 0.45, 0.2])
    x[:, 23] = rng.choice([1.0, 0.0, -1.0], n, p=[0.3, 0.5, 0.2])
    return x


def _sample_phish(rng: np.random.Generator, n: int) -> np.ndarray:
    """Targeted credential-theft: raw-IP / punycode links, mismatched Reply-To,
    failing authentication (brand spoof), urgent short bodies with a link."""
    x = np.zeros((n, FEATURE_COUNT), dtype=np.float64)
    x[:, 0] = 1.0
    x[:, 1] = rng.normal(55, 20, n).clip(5, 200)
    x[:, 2] = rng.beta(4, 6, n)
    x[:, 3] = rng.poisson(1.2, n)
    x[:, 4] = 1.0
    x[:, 5] = rng.poisson(1.0, n).clip(1, 10)
    x[:, 6] = (rng.random(n) < 0.6).astype(float)           # Reply-To often differs
    x[:, 7] = rng.poisson(2.5, n).clip(1, 30)              # a link to "verify"
    x[:, 8] = np.minimum(x[:, 7], rng.poisson(1.6, n)).clip(1, None)
    x[:, 9] = (rng.random(n) < 0.5).astype(float)           # raw-IP host
    x[:, 10] = (rng.random(n) < 0.35).astype(float)         # punycode host
    x[:, 11] = rng.normal(180, 70, n).clip(20, 700)        # long obfuscated URLs
    x[:, 12] = rng.normal(700, 300, n).clip(30, 6000)      # short, urgent
    x[:, 13] = x[:, 12] / rng.normal(5.8, 0.6, n).clip(3, 12)
    x[:, 14] = rng.beta(3, 8, n)
    x[:, 15] = rng.poisson(1.8, n)
    x[:, 16] = rng.normal(5.0, 0.9, n).clip(2, 12)
    x[:, 17] = rng.choice([1.0, 0.0], n, p=[0.8, 0.2])
    x[:, 18] = np.where(x[:, 17] > 0, rng.integers(0, 24, n), -1.0)
    x[:, 19] = np.where(x[:, 17] > 0, rng.integers(1, 8, n), -1.0)
    x[:, 20] = (x[:, 19] >= 6).astype(float)
    x[:, 21] = rng.choice([-1.0, -0.5, 0.0, 1.0], n, p=[0.4, 0.2, 0.2, 0.2])
    x[:, 22] = rng.choice([-1.0, 0.0, 1.0], n, p=[0.5, 0.3, 0.2])
    x[:, 23] = rng.choice([-1.0, 0.0, 1.0], n, p=[0.55, 0.3, 0.15])  # DMARC fail
    return x


def build_dataset(rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray]:
    ham = _sample_ham(rng, SAMPLES_PER_CLASS)
    spam = _sample_spam(rng, SAMPLES_PER_CLASS)
    phish = _sample_phish(rng, SAMPLES_PER_CLASS)
    x = np.vstack([ham, spam, phish]).astype(np.float32)
    y = np.concatenate([
        np.full(SAMPLES_PER_CLASS, LABEL_HAM),
        np.full(SAMPLES_PER_CLASS, LABEL_SPAM),
        np.full(SAMPLES_PER_CLASS, LABEL_PHISH),
    ])
    return x, y


def parity_cases(model: Pipeline) -> list[dict]:
    """A handful of fixed input vectors plus the probabilities this Python model
    assigns them. The Java parity test feeds the same vectors through ONNX Runtime
    and asserts the scores match within tolerance — proving export fidelity."""
    cases = {
        "all_zeros": [0.0] * FEATURE_COUNT,
        "calm_ham": [1, 42, 0.05, 0, 1, 1, 0, 1, 1, 0, 0, 55,
                     800, 140, 0.04, 0, 5.0, 1, 14, 3, 0, 1.0, 1.0, 1.0],
        "shouty_spam": [1, 88, 0.7, 5, 1, 1, 1, 9, 6, 0, 0, 160,
                        2200, 380, 0.6, 8, 5.3, 1, 2, 7, 1, 0.0, 0.0, 0.0],
        "ip_phish": [1, 60, 0.3, 1, 1, 1, 1, 3, 2, 1, 1, 220,
                     500, 90, 0.15, 2, 5.0, 1, 3, 4, 0, -1.0, -1.0, -1.0],
        "no_date": [1, 50, 0.1, 0, 1, 1, 0, 1, 1, 0, 0, 40,
                    600, 110, 0.05, 0, 5.0, 0, -1, -1, 0, 1.0, 1.0, 1.0],
    }
    out = []
    for name, vec in cases.items():
        arr = np.array([vec], dtype=np.float32)
        proba = model.predict_proba(arr)[0]
        out.append({
            "name": name,
            "features": [float(v) for v in vec],
            "hamScore": float(proba[LABEL_HAM]),
            "spamScore": float(proba[LABEL_SPAM]),
            "phishingScore": float(proba[LABEL_PHISH]),
        })
    return out


def main() -> None:
    rng = np.random.default_rng(SEED)
    x, y = build_dataset(rng)

    model = Pipeline([
        ("scale", StandardScaler()),
        ("clf", LogisticRegression(max_iter=2000, C=1.0)),
    ])
    model.fit(x, y)
    print(f"train accuracy: {model.score(x, y):.4f}")

    # zipmap=False → probabilities come out as a plain float tensor [n, 3] (column
    # order [ham, spam, phish]) instead of a ZipMap of dicts, so the Java side reads
    # a tensor by column index rather than parsing a map.
    onnx_model = to_onnx(
        model,
        initial_types=[("input", FloatTensorType([None, FEATURE_COUNT]))],
        target_opset=TARGET_OPSET,
        options={id(model.steps[-1][1]): {"zipmap": False}},
    )

    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    MODEL_OUT.write_bytes(onnx_model.SerializeToString())
    print(f"wrote {MODEL_OUT.relative_to(REPO_ROOT)} ({MODEL_OUT.stat().st_size} bytes)")

    print("ONNX inputs:")
    for inp in onnx_model.graph.input:
        print(f"  {inp.name}: {[d.dim_value for d in inp.type.tensor_type.shape.dim]}")
    print("ONNX outputs:")
    for out in onnx_model.graph.output:
        print(f"  {out.name}")

    cases = parity_cases(model)
    PARITY_OUT.parent.mkdir(parents=True, exist_ok=True)
    PARITY_OUT.write_text(json.dumps(cases, indent=2) + "\n")
    print(f"wrote {PARITY_OUT.relative_to(REPO_ROOT)} ({len(cases)} cases)")
    for c in cases:
        print(f"  {c['name']:14s} ham={c['hamScore']:.3f} spam={c['spamScore']:.3f} phish={c['phishingScore']:.3f}")


if __name__ == "__main__":
    main()
