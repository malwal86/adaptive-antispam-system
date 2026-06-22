"""Canonical text→hashed-vector schema for the local embedding model — the
cross-language contract.

Story 04.03 serves a local sentence embedder on the *same* ONNX Runtime as the
classifier (PRD §Architecture: "one runtime serves classifier + embeddings; zero
per-embedding API cost"). The embedder is a classic LSA-style encoder:

    text → tokenize → hashed n-gram counts (this file) → ONNX pipeline → 128-d vector

This module owns the *first half* — turning text into a fixed-width
``HASH_DIM`` vector of signed n-gram counts. It must stay byte-for-byte in step
with the Java side ``com.antispam.decision.embedding.TextHasher``: same
tokenization, same hash, same bucketing, same sign. The Java↔Python parity test
(``OnnxEmbeddingParityTest``) embeds the same texts on both sides and asserts the
output vectors match, so any drift here breaks loudly.

Everything downstream of the hashed vector — L2 normalization, the learned SVD
projection, and the final L2 normalization — lives *inside* the exported ONNX
graph (see ``train_embedding.py``), so the Java serving code only reproduces the
hashing in this file and reads a ready-made unit vector out of the runtime.

Why hashing rather than a fitted vocabulary: a vocabulary file is another
artifact to ship, version, and keep in lockstep across two languages. Hashing the
n-grams into a fixed width removes that artifact entirely — the only shared state
is this deterministic function — which is the same "no extra moving parts"
instinct behind serving on the one ONNX Runtime.
"""

from __future__ import annotations

# Must match TextHasher / OnnxEmbeddingModel on the Java side and the .onnx
# filename. The retrain loop (Epic 10) bumps all of them together if it ever
# replaces this bootstrap embedder.
EMBEDDING_VERSION = "embed-bootstrap-v1"

# Width of the hashed n-gram space (the ONNX model's input dimension). A power of
# two large enough that collisions between distinct n-grams are rare for
# email-sized text, small enough that the SVD components matrix stays tiny.
HASH_DIM = 4096

# Dimension of the served embedding (the ONNX model's output). The SVD keeps this
# many latent components; pgvector stores a vector(EMBED_DIM).
EMBED_DIM = 128

# Word n-gram sizes hashed into the vector. Unigrams carry topic; bigrams carry
# local phrasing, which is what makes near-duplicates (a reworded blast) land
# close together — the property Epic 06 clustering relies on.
NGRAM_SIZES = (1, 2)


def tokenize(text: str) -> list[str]:
    """Splits text into lowercase alphanumeric tokens.

    Deliberately ASCII-only and implemented as an explicit character scan rather
    than a regex or ``str.lower()``: Unicode case-folding and ``\\w`` differ
    subtly between Java and Python, and this function is half of a cross-language
    contract. Runs of ``[A-Za-z0-9]`` become tokens (``A-Z`` lowercased by adding
    0x20); every other character — including all non-ASCII letters — is a
    delimiter. ``TextHasher.tokenize`` on the Java side mirrors this exactly.
    """
    tokens: list[str] = []
    current: list[str] = []
    for ch in text or "":
        if "A" <= ch <= "Z":
            current.append(chr(ord(ch) + 32))
        elif "a" <= ch <= "z" or "0" <= ch <= "9":
            current.append(ch)
        elif current:
            tokens.append("".join(current))
            current = []
    if current:
        tokens.append("".join(current))
    return tokens


def java_hash(token: str) -> int:
    """Reproduces ``java.lang.String.hashCode()`` as a signed 32-bit int.

    The Java side hashes n-grams with the JDK's built-in ``String.hashCode``; we
    compute the identical value here so both languages bucket and sign a given
    n-gram the same way. Tokens are ASCII, so ``ord(ch)`` equals the Java ``char``.
    """
    h = 0
    for ch in token:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    if h >= 0x80000000:  # interpret the 32-bit value as signed
        h -= 0x100000000
    return h


def hashed_vector(text: str) -> list[float]:
    """Turns text into the ``HASH_DIM`` vector of signed n-gram counts.

    For each word n-gram (sizes in ``NGRAM_SIZES``, multi-word grams joined by a
    single space) the JDK hash picks the bucket (``floorMod`` into ``HASH_DIM``)
    and its sign bit picks +1/-1. The signed accumulation is the standard
    feature-hashing trick: opposite signs make collisions cancel on average
    instead of always inflating a bucket. The result is the raw input the ONNX
    pipeline normalizes and projects.
    """
    vector = [0.0] * HASH_DIM
    tokens = tokenize(text)
    for n in NGRAM_SIZES:
        for i in range(len(tokens) - n + 1):
            gram = " ".join(tokens[i:i + n])
            h = java_hash(gram)
            bucket = h % HASH_DIM  # positive modulus → non-negative, matches Math.floorMod
            vector[bucket] += -1.0 if h < 0 else 1.0
    return vector
