"""The Python flattener must mirror the Java ``ModelFeatureVector.toVector`` exactly.

The retrain pipeline (story 10.02) trains on feature vectors flattened from the
``FeatureSet`` JSON the API serves. That flattening is a cross-language contract: it
has to produce the same ordered 24-float vector the live Java classifier consumes, or
a retrained model is fit on a different layout than it is served on. These cases pin
the encoding rules — booleans → 1/0, the timing sentinels (-1 when no date), and the
ordinal auth encoding — against hand-computed vectors that match the checked-in
Java↔Python parity fixture.
"""

from feature_schema import AUTH_ENCODING, FEATURE_COUNT, flatten_feature_set


def _phish_feature_set():
    return {
        "header": {
            "hasSubject": True,
            "subjectLength": 60,
            "subjectUppercaseRatio": 0.3,
            "subjectExclamationCount": 1,
            "hasSender": True,
            "recipientCount": 1,
            "replyToDiffersFromFrom": True,
        },
        "link": {
            "urlCount": 3,
            "uniqueDomainCount": 2,
            "hasIpUrl": True,
            "hasPunycodeDomain": True,
            "maxUrlLength": 220,
        },
        "text": {
            "charCount": 500,
            "wordCount": 90,
            "uppercaseRatio": 0.15,
            "exclamationCount": 2,
            "avgWordLength": 5.0,
        },
        "timing": {"hasDate": True, "hourOfDayUtc": 3, "dayOfWeek": 4, "weekend": False},
        "auth": {"spf": "fail", "dkim": "fail", "dmarc": "fail"},
        "embedding": None,
    }


def test_flatten_matches_the_java_ip_phish_parity_vector():
    # The exact vector ModelFeatureVector produces for this FeatureSet — identical to
    # the "ip_phish" case in src/test/resources/models/parity-cases.json.
    expected = [
        1, 60, 0.3, 1, 1, 1, 1,
        3, 2, 1, 1, 220,
        500, 90, 0.15, 2, 5.0,
        1, 3, 4, 0,
        -1.0, -1.0, -1.0,
    ]
    vector = flatten_feature_set(_phish_feature_set())
    assert len(vector) == FEATURE_COUNT
    assert vector == [float(v) for v in expected]


def test_missing_date_flattens_hour_and_day_to_minus_one():
    fs = _phish_feature_set()
    fs["timing"] = {"hasDate": False, "hourOfDayUtc": None, "dayOfWeek": None, "weekend": False}
    vector = flatten_feature_set(fs)
    assert vector[17] == 0.0  # hasDate
    assert vector[18] == -1.0  # hourOfDayUtc sentinel
    assert vector[19] == -1.0  # dayOfWeek sentinel
    assert vector[20] == 0.0  # weekend


def test_auth_tokens_use_the_ordinal_encoding():
    fs = _phish_feature_set()
    fs["auth"] = {"spf": "pass", "dkim": "softfail", "dmarc": "neutral"}
    vector = flatten_feature_set(fs)
    assert vector[21] == AUTH_ENCODING["pass"]  # 1.0
    assert vector[22] == AUTH_ENCODING["softfail"]  # -0.5
    assert vector[23] == 0.0  # neutral / unasserted


def test_unknown_auth_token_falls_back_to_neutral_zero():
    fs = _phish_feature_set()
    fs["auth"] = {"spf": "weirdvalue", "dkim": None, "dmarc": "temperror"}
    vector = flatten_feature_set(fs)
    assert vector[21] == 0.0
    assert vector[22] == 0.0
    assert vector[23] == 0.0
