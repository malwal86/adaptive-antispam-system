"""Canonical model-input feature schema — the cross-language contract.

This ordered list of 24 features is the *input vector* the spam/phishing
classifier consumes. It must stay byte-for-byte in step with the Java mapper
``com.antispam.decision.model.ModelFeatureVector`` (same order, same encoding,
same length). The Java/Python parity test (``OnnxModelParityTest``) and the Java
vector test (``ModelFeatureVectorTest``) together guard that agreement: if either
side reorders or re-encodes a feature, parity breaks loudly.

The vector is derived from ``com.antispam.features.FeatureSet`` at
``EmailFeatureExtractor.FEATURE_VERSION`` — model and feature versions are
co-pinned (story 04.01 AC 5). Bump FEATURE_VERSION on the Java side and this
schema together; never silently.

The embedding field of FeatureSet (Epic 04.03) is deliberately excluded — it is
null until that story and is not part of this model's input.
"""

# (index, name) — index is implicit (list position) and is the wire order.
FEATURE_NAMES = [
    # -- header (7) --
    "header.hasSubject",                 # 0  0.0/1.0
    "header.subjectLength",              # 1  chars
    "header.subjectUppercaseRatio",     # 2  0..1
    "header.subjectExclamationCount",   # 3  count
    "header.hasSender",                 # 4  0.0/1.0
    "header.recipientCount",            # 5  count
    "header.replyToDiffersFromFrom",    # 6  0.0/1.0
    # -- link (5) --
    "link.urlCount",                    # 7  count
    "link.uniqueDomainCount",           # 8  count
    "link.hasIpUrl",                    # 9  0.0/1.0
    "link.hasPunycodeDomain",           # 10 0.0/1.0
    "link.maxUrlLength",                # 11 chars
    # -- text (5) --
    "text.charCount",                   # 12 chars
    "text.wordCount",                   # 13 count
    "text.uppercaseRatio",              # 14 0..1
    "text.exclamationCount",            # 15 count
    "text.avgWordLength",               # 16 chars
    # -- timing (4) --
    "timing.hasDate",                   # 17 0.0/1.0
    "timing.hourOfDayUtc",              # 18 0..23, or -1.0 when no date
    "timing.dayOfWeek",                 # 19 1..7, or -1.0 when no date
    "timing.weekend",                   # 20 0.0/1.0
    # -- auth (3) --
    "auth.spf",                         # 21 see AUTH_ENCODING
    "auth.dkim",                        # 22 see AUTH_ENCODING
    "auth.dmarc",                       # 23 see AUTH_ENCODING
]

FEATURE_COUNT = len(FEATURE_NAMES)  # 24

# Ordinal encoding of an Authentication-Results token. A "pass" earns trust, a
# "fail"/"softfail" loses it, everything unasserted/neutral is 0. This MUST match
# ModelFeatureVector.authScore on the Java side.
AUTH_ENCODING = {
    "pass": 1.0,
    "softfail": -0.5,
    "fail": -1.0,
    "neutral": 0.0,
    "none": 0.0,
    "temperror": 0.0,
    "permerror": 0.0,
    "policy": 0.0,
    "unknown": 0.0,
}
AUTH_DEFAULT = 0.0

# Integer class labels — chosen so sklearn's sorted class order is [ham, spam,
# phish], which fixes the ONNX probability-tensor column order the Java side reads:
# spam_score = probabilities[1], phishing_score = probabilities[2].
LABEL_HAM = 0
LABEL_SPAM = 1
LABEL_PHISH = 2
