"""Train a retrain *candidate* from the labeled-data export, export it to ONNX, and
calibrate it — the scheduled "CI/CD for ML" step (story 10.02, PRD §Subsystem 9 step 2).

"Train where it's easy, serve where it's fast": this fits a scikit-learn classifier on
the exported corpus (seed + weighted feedback + arena, story 10.01), exports it to ONNX
so the Java app can serve it in-process, and **calibrates** the maliciousness score with
isotonic regression on a held-out split — attaching the reliability report the promotion
gate (10.03) and the live calibrator (04.02) consume. The output is a *candidate*: a
versioned artifact plus metadata (``model_version``, ``feature_version``, ``π_train``,
provenance counts, calibration report) written to a staging directory. It is **not**
activated — promotion is a separate, gated step (10.04). A run that cannot produce an
honest model (too little data, a missing class, a malformed vector, a bad ONNX export)
raises :class:`TrainingError` and writes **nothing**, so a broken nightly never leaves a
half-baked candidate behind.

Run:
  .venv/bin/python ml/train_candidate.py --export training-export.json --out candidate/
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from feature_schema import FEATURE_COUNT, LABEL_HAM, LABEL_PHISH, LABEL_SPAM

# Fixed seed so the split, the fit, and therefore the candidate are reproducible: the
# same export always yields the same model and the same model_version (AC: reproducible).
SEED = 20260622

# Conservative opset — matches train_classifier.py so the checked-in Java ONNX Runtime
# loads the candidate graph the same way it loads the bootstrap model.
TARGET_OPSET = 15

# Calibration is measured the way CalibrationEvaluator measures it on the Java side
# (story 04.02): 10 equal-width bins over [0,1], count-weighted ECE.
BIN_COUNT = 10

# The ONNX input/output names the Java serving path expects (see OnnxModel).
INPUT_NAME = "input"
PROBABILITIES_OUTPUT = "probabilities"

LABEL_TO_INT = {"ham": LABEL_HAM, "spam": LABEL_SPAM, "phish": LABEL_PHISH}
INT_TO_LABEL = {v: k for k, v in LABEL_TO_INT.items()}


class TrainingError(ValueError):
    """A retrain run that cannot honestly produce a candidate. Raised before any
    artifact is written so a failed run yields no half-baked output (story 10.02 AC 4)."""


# --------------------------------------------------------------------------- validation


def _validate(export: dict, min_samples: int) -> None:
    examples = export.get("examples")
    if not isinstance(examples, list) or not examples:
        raise TrainingError("export has no examples")
    if len(examples) < min_samples:
        raise TrainingError(
            f"too few training examples: {len(examples)} < required {min_samples}")
    for i, ex in enumerate(examples):
        features = ex.get("features")
        if not isinstance(features, list) or len(features) != FEATURE_COUNT:
            raise TrainingError(
                f"example {i} ({ex.get('emailId')}) has {len(features) if isinstance(features, list) else 'no'} "
                f"features, expected {FEATURE_COUNT}")
        if ex.get("label") not in LABEL_TO_INT:
            raise TrainingError(f"example {i} has unknown label {ex.get('label')!r}")
        weight = ex.get("weight")
        if not isinstance(weight, (int, float)) or weight <= 0 or not np.isfinite(weight):
            raise TrainingError(f"example {i} has non-positive/invalid weight {weight!r}")
    labels = {ex["label"] for ex in examples}
    if "ham" not in labels or labels <= {"ham"}:
        # Need both a ham class and at least one malicious class, or π_train degenerates
        # to 0/1 and log-odds fusion (04.04) cannot subtract a finite prior.
        raise TrainingError(f"export must contain ham and at least one malicious class, got {sorted(labels)}")


# ------------------------------------------------------------------------ calibration


def _reliability_curve(predicted: np.ndarray, actual: np.ndarray, bin_count: int) -> list[dict]:
    """The reliability diagram, mirroring CalibrationEvaluator.reliabilityCurve: equal-width
    bins, the top bin inclusive of 1.0, empty bins kept so the curve has a fixed shape."""
    width = 1.0 / bin_count
    bins = []
    for b in range(bin_count):
        lo, hi = b * width, (b + 1) * width
        if b == bin_count - 1:
            in_bin = (predicted >= lo) & (predicted <= hi)
        else:
            in_bin = (predicted >= lo) & (predicted < hi)
        n = int(in_bin.sum())
        mean_pred = float(predicted[in_bin].mean()) if n else 0.0
        observed = float(actual[in_bin].mean()) if n else 0.0
        bins.append({
            "lowerEdge": lo,
            "upperEdge": hi,
            "count": n,
            "meanPredicted": mean_pred,
            "observedFrequency": observed,
        })
    return bins


def _ece(predicted: np.ndarray, actual: np.ndarray, bin_count: int) -> float:
    """Count-weighted average per-bin |meanPredicted − observedFrequency| — the Expected
    Calibration Error, identical in definition to CalibrationEvaluator.expectedCalibrationError."""
    if predicted.size == 0:
        return 0.0
    total = predicted.size
    return sum(
        (b["count"] / total) * abs(b["meanPredicted"] - b["observedFrequency"])
        for b in _reliability_curve(predicted, actual, bin_count)
    )


def _calibrate(model: Pipeline, x_eval: np.ndarray, y_eval: np.ndarray, model_version: str) -> tuple[dict, IsotonicRegression]:
    """Fit isotonic calibration on the held-out eval split and measure raw-vs-calibrated
    reliability over it. The calibrated quantity is the maliciousness probability
    P(spam)+P(phish) — the single score fusion and the live calibrator (04.02) consume."""
    raw = model.predict_proba(x_eval)[:, [LABEL_SPAM, LABEL_PHISH]].sum(axis=1)
    raw = np.clip(raw, 0.0, 1.0)
    actual = (y_eval != LABEL_HAM).astype(float)

    iso = IsotonicRegression(out_of_bounds="clip")
    iso.fit(raw, actual)
    calibrated = np.clip(iso.predict(raw), 0.0, 1.0)

    ece_raw = _ece(raw, actual, BIN_COUNT)
    ece_cal = _ece(calibrated, actual, BIN_COUNT)
    report = {
        "modelVersion": model_version,
        "method": "isotonic",
        "sampleCount": int(x_eval.shape[0]),
        "binCount": BIN_COUNT,
        "eceRaw": ece_raw,
        "eceCalibrated": ece_cal,
        "eceImprovement": ece_raw - ece_cal,
        "calibratedBins": _reliability_curve(calibrated, actual, BIN_COUNT),
        # The fitted step-function (thresholds → calibrated probability), so the
        # promotion step (10.04) can install this exact calibrator on the serving path.
        "isotonic": {
            "x": [float(v) for v in iso.X_thresholds_],
            "y": [float(v) for v in iso.y_thresholds_],
        },
    }
    return report, iso


# ---------------------------------------------------------------------------- export


def _model_version(export: dict) -> str:
    """A deterministic version derived from the training corpus: same export → same id,
    different data → different id. So the candidate's identity tracks exactly what it was
    trained on (no wall clock, reproducible)."""
    examples = sorted(export["examples"], key=lambda e: e["emailId"])
    canonical = json.dumps(
        {"featureVersion": export["featureVersion"],
         "examples": [[e["emailId"], e["label"], e["weight"], e["features"]] for e in examples]},
        sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(canonical.encode()).hexdigest()[:8]
    return f"candidate-fv{export['featureVersion']}-{digest}"


def _to_onnx(model: Pipeline) -> bytes:
    onnx_model = to_onnx(
        model,
        initial_types=[(INPUT_NAME, FloatTensorType([None, FEATURE_COUNT]))],
        target_opset=TARGET_OPSET,
        options={id(model.steps[-1][1]): {"zipmap": False}},
    )
    return onnx_model.SerializeToString()


def _parity_cases(model: Pipeline, x: np.ndarray, sample: int = 8) -> list[dict]:
    """A handful of input vectors with the probabilities this sklearn model assigns —
    the fixture a Java parity check (and the in-run ONNX self-check below) replays."""
    cases = []
    for i in range(min(sample, x.shape[0])):
        vec = x[i]
        proba = model.predict_proba(vec.reshape(1, -1))[0]
        cases.append({
            "name": f"sample_{i}",
            "features": [float(v) for v in vec],
            "hamScore": float(proba[LABEL_HAM]),
            "spamScore": float(proba[LABEL_SPAM]),
            "phishingScore": float(proba[LABEL_PHISH]),
        })
    return cases


def _assert_onnx_parity(onnx_bytes: bytes, cases: list[dict]) -> None:
    """Score the exported graph through ONNX Runtime — the same engine the Java app serves
    with — and fail the run if it disagrees with sklearn, so a bad export produces no candidate."""
    session = ort.InferenceSession(onnx_bytes)
    for case in cases:
        vec = np.array([case["features"]], dtype=np.float32)
        probs = session.run([PROBABILITIES_OUTPUT], {INPUT_NAME: vec})[0][0]
        if (abs(probs[LABEL_SPAM] - case["spamScore"]) > 1e-4
                or abs(probs[LABEL_PHISH] - case["phishingScore"]) > 1e-4):
            raise TrainingError(
                f"ONNX export disagrees with sklearn on {case['name']}: "
                f"onnx=({probs[LABEL_SPAM]:.5f},{probs[LABEL_PHISH]:.5f}) "
                f"sklearn=({case['spamScore']:.5f},{case['phishingScore']:.5f})")


# ------------------------------------------------------------------------------- run


def run(export: dict, out_dir, *, min_samples: int = 40, eval_fraction: float = 0.25) -> dict:
    """Train, export, and calibrate a candidate from ``export`` into ``out_dir``; return the
    candidate metadata. Raises :class:`TrainingError` (before writing anything) if the export
    cannot yield an honest model."""
    _validate(export, min_samples)
    out_dir = Path(out_dir)

    examples = export["examples"]
    x = np.array([e["features"] for e in examples], dtype=np.float32)
    y = np.array([LABEL_TO_INT[e["label"]] for e in examples])
    w = np.array([float(e["weight"]) for e in examples], dtype=np.float64)

    # Stratified hold-out so the eval split has every class to calibrate against and the
    # reliability measurement is leakage-free (the model never trained on it).
    try:
        x_tr, x_ev, y_tr, y_ev, w_tr, _ = train_test_split(
            x, y, w, test_size=eval_fraction, random_state=SEED, stratify=y)
    except ValueError as e:
        raise TrainingError(f"cannot split export for calibration: {e}") from e

    model = Pipeline([
        ("scale", StandardScaler()),
        ("clf", LogisticRegression(max_iter=2000, C=1.0)),
    ])
    model.fit(x_tr, y_tr, clf__sample_weight=w_tr)

    model_version = _model_version(export)
    onnx_bytes = _to_onnx(model)
    parity = _parity_cases(model, x_ev)
    _assert_onnx_parity(onnx_bytes, parity)

    report, _ = _calibrate(model, x_ev, y_ev, model_version)

    # π_train: the weighted base rate of abuse over the data the exported model was fit on.
    # Fusion (04.04) subtracts logit(π_train) once; it is a property of the training corpus,
    # so it travels in the sidecar the Java side reads by model_version (ModelMetadata).
    malicious_weight = float(w_tr[y_tr != LABEL_HAM].sum())
    training_base_rate = malicious_weight / float(w_tr.sum())

    counts_by_source: dict[str, int] = {}
    counts_by_label: dict[str, int] = {}
    for ex in examples:
        counts_by_source[ex["source"]] = counts_by_source.get(ex["source"], 0) + 1
        counts_by_label[ex["label"]] = counts_by_label.get(ex["label"], 0) + 1

    metadata = {
        "modelVersion": model_version,
        "featureVersion": export["featureVersion"],
        "trainingBaseRate": training_base_rate,
        "trainingSampleCount": int(x_tr.shape[0]),
        "evalSampleCount": int(x_ev.shape[0]),
        "trainingMaliciousCount": int((y_tr != LABEL_HAM).sum()),
        "classLabels": ["ham", "spam", "phish"],
        "countsBySource": counts_by_source,
        "countsByLabel": counts_by_label,
        "calibration": {
            "method": report["method"],
            "eceRaw": report["eceRaw"],
            "eceCalibrated": report["eceCalibrated"],
            "sampleCount": report["sampleCount"],
            "binCount": report["binCount"],
        },
        "candidate": True,
        "note": (
            "Retrain candidate (story 10.02): staged, NOT activated. trainingBaseRate = "
            "weighted P(spam or phish) over the training split, subtracted once in log-odds "
            "fusion (04.04). Promotion is gated separately (10.03/10.04)."
        ),
    }

    # Only now that everything succeeded do we write — a failed run above left out_dir empty.
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"spam-classifier-{model_version}.onnx").write_bytes(onnx_bytes)
    (out_dir / f"spam-classifier-{model_version}.metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n")
    (out_dir / f"spam-classifier-{model_version}.calibration.json").write_text(
        json.dumps(report, indent=2) + "\n")
    (out_dir / "parity-cases.json").write_text(json.dumps(parity, indent=2) + "\n")
    return metadata


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train a calibrated retrain candidate from a labeled export.")
    parser.add_argument("--export", required=True, help="path to the labeled training-export JSON (story 10.01)")
    parser.add_argument("--out", required=True, help="staging directory for the candidate artifact")
    parser.add_argument("--min-samples", type=int, default=40, help="minimum examples required to train")
    args = parser.parse_args(argv)

    export = json.loads(Path(args.export).read_text())
    try:
        meta = run(export, args.out, min_samples=args.min_samples)
    except TrainingError as e:
        print(f"::error::retrain candidate not produced: {e}", file=sys.stderr)
        return 1

    print(f"candidate {meta['modelVersion']} "
          f"(featureVersion={meta['featureVersion']}, π_train={meta['trainingBaseRate']:.4f}, "
          f"train={meta['trainingSampleCount']}, eval={meta['evalSampleCount']}, "
          f"ECE {meta['calibration']['eceRaw']:.4f}→{meta['calibration']['eceCalibrated']:.4f}) "
          f"staged in {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
