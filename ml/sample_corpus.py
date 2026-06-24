"""A deterministic synthetic labeled export for the retrain pipeline.

Builds a training export in the same shape ``fetch_training_data`` assembles from the
live API: a ``featureVersion`` and a list of examples, each with a flattened 24-float
feature vector, a label, a weight, a source, and a stable email id. The class-conditioned
distributions are a slimmed-down echo of ``train_classifier.py`` — enough signal that a
logistic model separates the classes, so calibration has something honest to measure —
and everything is driven by a fixed seed so the same call yields byte-identical examples
(the export, and therefore the candidate, is reproducible).

Two consumers: the retrain-pipeline tests import :func:`make_export` for in-memory
fixtures, and running this module as a script writes the checked-in
``fixtures/sample-training-export.json`` the scheduled workflow falls back to when no live
API is configured — so the nightly always has a corpus to prove the pipeline end-to-end.

Regenerate:
  .venv/bin/python ml/sample_corpus.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from feature_schema import FEATURE_COUNT


def _ham(rng, n):
    x = np.zeros((n, FEATURE_COUNT))
    x[:, 0] = 1.0
    x[:, 2] = rng.beta(2, 12, n)          # low subject uppercase
    x[:, 7] = rng.poisson(1.0, n)         # few urls
    x[:, 14] = rng.beta(2, 18, n)         # calm body
    x[:, 21] = 1.0                        # spf pass
    x[:, 22] = 1.0                        # dkim pass
    return x, "ham"


def _spam(rng, n):
    x = np.zeros((n, FEATURE_COUNT))
    x[:, 0] = 1.0
    x[:, 2] = rng.beta(6, 4, n)           # shouty subject
    x[:, 7] = rng.poisson(6.0, n)         # many urls
    x[:, 14] = rng.beta(5, 6, n)          # shouty body
    x[:, 21] = 0.0
    x[:, 22] = 0.0
    return x, "spam"


def _phish(rng, n):
    x = np.zeros((n, FEATURE_COUNT))
    x[:, 0] = 1.0
    x[:, 9] = 1.0                         # raw-ip url
    x[:, 10] = 1.0                        # punycode
    x[:, 7] = rng.poisson(2.5, n)
    x[:, 21] = -1.0                       # spf fail
    x[:, 23] = -1.0                       # dmarc fail
    return x, "phish"


def make_export(n_per_class: int = 60, seed: int = 7, feature_version: int = 1) -> dict:
    """A labeled export with ``n_per_class`` examples of each class, deterministic for
    a given ``seed``. Email ids are derived from the index so they are stable and unique."""
    rng = np.random.default_rng(seed)
    examples = []
    idx = 0
    for builder in (_ham, _spam, _phish):
        block, label = builder(rng, n_per_class)
        for row in block:
            examples.append({
                "emailId": f"00000000-0000-0000-0000-{idx:012d}",
                "label": label,
                "weight": 1.0,
                "source": "seed",
                "features": [float(v) for v in row],
            })
            idx += 1
    return {"featureVersion": feature_version, "featureCount": FEATURE_COUNT, "examples": examples}


# The checked-in fallback corpus: large enough to clear the default min-sample floor and
# split per-class for calibration, fixed seed so it is byte-reproducible.
_FIXTURE_OUT = Path(__file__).resolve().parent / "fixtures" / "sample-training-export.json"


def main() -> None:
    export = make_export(n_per_class=80, seed=20260622)
    _FIXTURE_OUT.parent.mkdir(parents=True, exist_ok=True)
    _FIXTURE_OUT.write_text(json.dumps(export, indent=2) + "\n")
    print(f"wrote {_FIXTURE_OUT} ({len(export['examples'])} examples)")


if __name__ == "__main__":
    main()
