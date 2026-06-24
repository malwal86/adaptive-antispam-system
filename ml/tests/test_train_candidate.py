"""The retrain training step (story 10.02): export → train → ONNX → calibrate → candidate.

These pin the contract the scheduled workflow depends on: a valid labeled export yields
a calibrated, versioned ONNX candidate with complete metadata and a calibration report,
the ONNX faithfully reproduces the trained sklearn model (the same ONNX Runtime Java
serves with), the model version is a deterministic function of the data (reproducible),
and an invalid run fails loudly without leaving a half-baked artifact behind.
"""

import json

import numpy as np
import onnxruntime as ort
import pytest

import train_candidate as tc
from feature_schema import FEATURE_COUNT
from sample_corpus import make_export


def test_produces_calibrated_versioned_candidate_with_metadata(tmp_path):
    meta = tc.run(make_export(), tmp_path)

    # AC: a candidate ONNX artifact is produced, exported and calibrated.
    onnx_path = tmp_path / f"spam-classifier-{meta['modelVersion']}.onnx"
    calib_path = tmp_path / f"spam-classifier-{meta['modelVersion']}.calibration.json"
    assert onnx_path.exists() and onnx_path.stat().st_size > 0
    assert calib_path.exists()

    # AC: metadata records model_version, feature_version, π_train, and provenance counts.
    assert meta["modelVersion"].startswith("candidate-")
    assert meta["featureVersion"] == 1
    assert 0.0 < meta["trainingBaseRate"] < 1.0
    assert meta["countsBySource"] == {"seed": meta["trainingSampleCount"] + meta["evalSampleCount"]}
    assert set(meta["countsByLabel"]) == {"ham", "spam", "phish"}
    assert meta["calibration"]["method"] == "isotonic"

    # AC: a calibration report is attached — raw vs calibrated ECE + reliability curve.
    report = json.loads(calib_path.read_text())
    assert report["binCount"] == 10
    assert len(report["calibratedBins"]) == 10
    assert report["calibratedBins"][0].keys() >= {
        "lowerEdge", "upperEdge", "count", "meanPredicted", "observedFrequency"}
    # Isotonic fit on the same held-out set it is measured on never increases ECE.
    assert report["eceCalibrated"] <= report["eceRaw"] + 1e-9


def test_onnx_matches_sklearn_within_tolerance(tmp_path):
    """Export fidelity: scoring the candidate through ONNX Runtime — the same engine the
    Java app serves with — must match the trained sklearn model (parity)."""
    meta = tc.run(make_export(), tmp_path)
    onnx_path = tmp_path / f"spam-classifier-{meta['modelVersion']}.onnx"
    parity = json.loads((tmp_path / "parity-cases.json").read_text())

    session = ort.InferenceSession(onnx_path.read_bytes())
    for case in parity:
        vec = np.array([case["features"]], dtype=np.float32)
        probs = session.run(["probabilities"], {"input": vec})[0][0]
        assert probs[1] == pytest.approx(case["spamScore"], abs=1e-5)
        assert probs[2] == pytest.approx(case["phishingScore"], abs=1e-5)


def test_model_version_is_deterministic(tmp_path):
    a = tc.run(make_export(seed=3), tmp_path / "a")
    b = tc.run(make_export(seed=3), tmp_path / "b")
    assert a["modelVersion"] == b["modelVersion"]
    # Different data → different version (the hash tracks the corpus it was trained on).
    c = tc.run(make_export(seed=99), tmp_path / "c")
    assert c["modelVersion"] != a["modelVersion"]


def test_too_few_samples_fails_loudly_and_writes_no_artifact(tmp_path):
    with pytest.raises(tc.TrainingError):
        tc.run(make_export(n_per_class=2), tmp_path, min_samples=40)
    assert list(tmp_path.glob("*.onnx")) == []


def test_single_class_export_is_rejected(tmp_path):
    export = make_export()
    for ex in export["examples"]:
        ex["label"] = "ham"  # no malicious class → π_train would be 0, fusion breaks
    with pytest.raises(tc.TrainingError):
        tc.run(export, tmp_path, min_samples=1)
    assert list(tmp_path.glob("*.onnx")) == []


def test_wrong_feature_length_is_rejected(tmp_path):
    export = make_export()
    export["examples"][0]["features"] = [0.0] * (FEATURE_COUNT - 1)
    with pytest.raises(tc.TrainingError):
        tc.run(export, tmp_path)
    assert list(tmp_path.glob("*.onnx")) == []
