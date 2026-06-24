"""Assemble the trainable labeled export from the live API (story 10.02 input wiring).

The labeled-data export (story 10.01, ``GET /retrain/export``) carries labels, weights,
provenance, and a feature_version — but not the feature *vectors* themselves. The features
live in ``email_features`` and are served per email at ``GET /emails/{id}/features``. This
module joins the two into the flattened, trainable export ``train_candidate`` consumes:
every labeled example paired with the ordered 24-float vector flattened from its
``FeatureSet`` (via the cross-language :func:`feature_schema.flatten_feature_set`).

An example whose features have not been extracted at the export's feature_version is
dropped — you cannot train a label without the vector it describes — and the (separate)
training step enforces the minimum-sample floor. The flattening and HTTP I/O are kept apart
so the assembly logic is testable without a network: :func:`build_export` takes the two
fetch callables, and :func:`main` wires them to real HTTP.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Callable, Optional

from feature_schema import FEATURE_COUNT, flatten_feature_set


def build_export(
        get_labeled_export: Callable[[], dict],
        get_features: Callable[[str], Optional[dict]],
) -> dict:
    """Join the labeled export to per-email feature vectors into a trainable export.

    :param get_labeled_export: returns the ``GET /retrain/export`` body (labels).
    :param get_features: returns the ``GET /emails/{id}/features`` body for an email, or
        ``None`` if features have not been extracted for it (a 404).
    :returns: ``{featureVersion, featureCount, examples:[{emailId,label,weight,source,features}]}``
        with each example's ``FeatureSet`` flattened to an ordered 24-float vector; examples
        without features are dropped.
    """
    labeled = get_labeled_export()
    feature_version = labeled["featureVersion"]
    examples = []
    dropped = 0
    for ex in labeled["examples"]:
        features = get_features(ex["emailId"])
        if features is None or "features" not in features:
            dropped += 1
            continue
        examples.append({
            "emailId": ex["emailId"],
            "label": ex["label"],
            "weight": ex["weight"],
            "source": ex["source"],
            "features": flatten_feature_set(features["features"]),
        })
    if dropped:
        print(f"dropped {dropped} labeled example(s) with no features at version {feature_version}",
              file=sys.stderr)
    return {"featureVersion": feature_version, "featureCount": FEATURE_COUNT, "examples": examples}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fetch the labeled export and join it to feature vectors into a trainable export.")
    parser.add_argument("--api", required=True, help="base URL of the running API (e.g. https://host)")
    parser.add_argument("--out", required=True, help="path to write the trainable export JSON")
    parser.add_argument("--timeout", type=float, default=30.0, help="per-request timeout, seconds")
    args = parser.parse_args(argv)

    import requests  # imported here so the testable core has no hard HTTP dependency

    base = args.api.rstrip("/")
    session = requests.Session()

    def get_labeled_export() -> dict:
        resp = session.get(f"{base}/retrain/export", timeout=args.timeout)
        resp.raise_for_status()
        return resp.json()

    def get_features(email_id: str) -> Optional[dict]:
        resp = session.get(f"{base}/emails/{email_id}/features", timeout=args.timeout)
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        return resp.json()

    export = build_export(get_labeled_export, get_features)
    Path(args.out).write_text(json.dumps(export, indent=2) + "\n")
    print(f"wrote {args.out}: {len(export['examples'])} trainable examples "
          f"at featureVersion={export['featureVersion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
