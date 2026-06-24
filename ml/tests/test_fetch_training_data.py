"""Assembling the trainable export from the live API (story 10.02 input wiring).

``fetch_training_data`` turns the labeled export (10.01, labels only) plus the per-email
feature records into the flattened, trainable export ``train_candidate`` consumes. These
pin the assembly contract with injected fakes (no network): labels are joined to their
flattened feature vectors, the export's feature_version carries through, and an example
whose features are missing at that version is dropped — you cannot train a label without
the vector it was labeled on — rather than crashing the whole run.
"""

import pytest

import fetch_training_data as ftd


def _features(spf="pass"):
    return {
        "header": {"hasSubject": True, "subjectLength": 10, "subjectUppercaseRatio": 0.0,
                   "subjectExclamationCount": 0, "hasSender": True, "recipientCount": 1,
                   "replyToDiffersFromFrom": False},
        "link": {"urlCount": 0, "uniqueDomainCount": 0, "hasIpUrl": False,
                 "hasPunycodeDomain": False, "maxUrlLength": 0},
        "text": {"charCount": 100, "wordCount": 20, "uppercaseRatio": 0.0,
                 "exclamationCount": 0, "avgWordLength": 5.0},
        "timing": {"hasDate": True, "hourOfDayUtc": 12, "dayOfWeek": 3, "weekend": False},
        "auth": {"spf": spf, "dkim": "pass", "dmarc": "pass"},
        "embedding": None,
    }


def _export():
    return {
        "featureVersion": 1,
        "examples": [
            {"emailId": "id-1", "label": "ham", "weight": 1.0, "source": "seed"},
            {"emailId": "id-2", "label": "spam", "weight": 0.7, "source": "feedback"},
        ],
    }


def test_joins_labels_to_flattened_feature_vectors():
    features = {"id-1": {"features": _features("pass")}, "id-2": {"features": _features("fail")}}

    export = ftd.build_export(lambda: _export(), lambda eid: features.get(eid))

    assert export["featureVersion"] == 1
    assert export["featureCount"] == 24
    assert len(export["examples"]) == 2
    first = export["examples"][0]
    assert first["emailId"] == "id-1"
    assert first["label"] == "ham"
    assert first["weight"] == 1.0
    assert first["source"] == "seed"
    assert len(first["features"]) == 24
    assert first["features"][21] == 1.0  # spf pass
    assert export["examples"][1]["features"][21] == -1.0  # spf fail


def test_drops_examples_whose_features_are_missing():
    # id-2 has no features extracted at this version (a 404) → dropped, not fatal.
    features = {"id-1": {"features": _features()}}

    export = ftd.build_export(lambda: _export(), lambda eid: features.get(eid))

    assert [e["emailId"] for e in export["examples"]] == ["id-1"]


def test_empty_after_dropping_is_allowed_here_training_step_enforces_minimums():
    export = ftd.build_export(lambda: _export(), lambda eid: None)
    assert export["examples"] == []
    assert export["featureVersion"] == 1
