"""Train the bootstrap local sentence embedder and export it to ONNX.

Story 04.03: serve embeddings on the *same* ONNX Runtime as the classifier, at
zero per-embedding API cost (PRD §Architecture). This is a classic LSA encoder —
hashed n-gram counts projected to a dense ``EMBED_DIM`` vector by a truncated SVD,
L2-normalized so cosine similarity is a dot product. It is the *bootstrap* embedder
that stands the serving + storage path up end-to-end; like the bootstrap
classifier it is fit on a synthetic, fixed-seed corpus so the artifact is
reproducible and checked into git. The retrain loop (Epic 10) can later refit the
SVD on the real corpus; the Java serving code and the hashing contract do not change.

The Java side reproduces only the hashing (``embedding_schema.hashed_vector`` ⇆
``TextHasher``); everything else — input L2 norm, the SVD projection, output L2
norm — lives inside the exported ONNX graph, so Java reads a ready-made unit
vector out of the runtime.

Outputs (regenerate by running this script — see ml/README.md):
  - src/main/resources/models/embed-bootstrap-v1.onnx          (served by Java)
  - src/test/resources/models/embedding-parity-cases.json      (Java↔Python parity fixture)

Run:
  .venv/bin/python ml/train_embedding.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType
from sklearn.decomposition import TruncatedSVD
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import Normalizer

from embedding_schema import EMBED_DIM, EMBEDDING_VERSION, HASH_DIM, hashed_vector

REPO_ROOT = Path(__file__).resolve().parent.parent
MODEL_OUT = REPO_ROOT / "src/main/resources/models" / f"{EMBEDDING_VERSION}.onnx"
PARITY_OUT = REPO_ROOT / "src/test/resources/models" / "embedding-parity-cases.json"

# Fixed seed so the synthetic corpus — and therefore the fitted SVD and the
# exported artifact — is reproducible across machines and CI.
SEED = 20260622

# Conservative opset, matching the classifier exporter: old enough that the
# checked-in Java ONNX Runtime (1.26.x) loads the graph regardless of the (newer)
# Python exporter default.
TARGET_OPSET = 15

# A handful of topic templates with interchangeable slots. The SVD learns the
# directions that separate these topics, so same-topic text lands close and
# cross-topic text lands far — exactly the structure clustering (Epic 06) wants.
# Synthetic and templated on purpose: it keeps the artifact reproducible and the
# story self-contained, the same choice the bootstrap classifier made.
TOPICS: dict[str, list[str]] = {
    "shipping": [
        "Your order {id} has shipped and is on its way to {place}.",
        "Tracking update: package {id} is out for delivery in {place} today.",
        "Good news, your parcel {id} will arrive at {place} tomorrow.",
        "We have dispatched your shipment {id}; expected delivery to {place} soon.",
    ],
    "billing": [
        "Your invoice {id} of {amount} dollars is now available to download.",
        "Payment received: we have credited {amount} dollars to account {id}.",
        "A receipt for your {amount} dollar purchase, reference {id}, is attached.",
        "Your monthly statement {id} totaling {amount} dollars is ready.",
    ],
    "security_alert": [
        "Security alert: a new sign in to your account from {place} was detected.",
        "We noticed an unusual login to account {id} from a device in {place}.",
        "Verify it was you: someone signed in to {id} from {place} just now.",
        "Suspicious activity on account {id}; review the recent login from {place}.",
    ],
    "password_reset": [
        "Use code {id} to reset the password for your account, expires soon.",
        "Reset your password now with the verification code {id} we just sent.",
        "Your password reset link is ready; enter code {id} to continue.",
        "To finish resetting your password, confirm the one time code {id}.",
    ],
    "newsletter": [
        "This week in tech: {id} stories on startups, funding and product launches.",
        "Our weekly digest brings you {id} articles on industry news and trends.",
        "Catch up on the latest: {id} hand picked reads from our newsletter team.",
        "Here are the top {id} headlines and features from this week's edition.",
    ],
    "promo": [
        "Huge sale, save {amount} percent on everything this weekend only, shop now.",
        "Limited time offer: take {amount} percent off your next order today.",
        "Flash deal, {amount} percent discount on all items, do not miss out.",
        "Exclusive coupon inside, claim {amount} percent off before it expires.",
    ],
    "meeting": [
        "Reminder: your meeting with {place} is scheduled for {id} this afternoon.",
        "Let's sync about the project; are you free at {id} to meet the {place} team?",
        "Calendar invite: planning call with {place} at {id}, please accept.",
        "Following up to confirm our {id} meeting with the {place} group.",
    ],
    "support": [
        "Thanks for contacting support; ticket {id} has been created for your issue.",
        "Our team is reviewing your request {id} and will reply within one day.",
        "Update on support case {id}: a specialist has been assigned to help you.",
        "We have resolved ticket {id}; please let us know if anything else comes up.",
    ],
}

PLACES = ["seattle", "london", "austin", "berlin", "tokyo", "toronto", "dublin", "sydney"]


def build_corpus(rng: np.random.Generator) -> list[str]:
    """Renders the topic templates with random slot fills into a flat corpus."""
    docs: list[str] = []
    for templates in TOPICS.values():
        for template in templates:
            for _ in range(20):  # 8 topics × 4 templates × 20 = 640 documents
                docs.append(template.format(
                    id=int(rng.integers(1000, 9999)),
                    amount=int(rng.integers(5, 90)),
                    place=rng.choice(PLACES),
                ))
    rng.shuffle(docs)
    return docs


def to_matrix(texts: list[str]) -> np.ndarray:
    """Hashes each text through the cross-language contract into a dense matrix."""
    return np.array([hashed_vector(t) for t in texts], dtype=np.float32)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b))  # embeddings are L2-normalized, so dot == cosine


def main() -> None:
    rng = np.random.default_rng(SEED)
    corpus = build_corpus(rng)
    matrix = to_matrix(corpus)
    print(f"corpus: {len(corpus)} docs × {HASH_DIM} hashed features")

    # input L2 norm → truncated SVD (LSA) → output L2 norm, all inside the graph.
    model = Pipeline([
        ("normalize_in", Normalizer(norm="l2")),
        ("svd", TruncatedSVD(n_components=EMBED_DIM, random_state=SEED)),
        ("normalize_out", Normalizer(norm="l2")),
    ])
    model.fit(matrix)
    explained = float(model.named_steps["svd"].explained_variance_ratio_.sum())
    print(f"SVD keeps {EMBED_DIM} components, explained variance {explained:.3f}")

    onnx_model = to_onnx(
        model,
        initial_types=[("input", FloatTensorType([None, HASH_DIM]))],
        target_opset=TARGET_OPSET,
    )
    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    MODEL_OUT.write_bytes(onnx_model.SerializeToString())
    print(f"wrote {MODEL_OUT.relative_to(REPO_ROOT)} ({MODEL_OUT.stat().st_size} bytes)")

    # Parity fixture: embed each text through the *exported ONNX graph* (exactly
    # what Java loads), not the sklearn pipeline, so the fixture proves end-to-end
    # export + serving fidelity. Java hashes the same text identically and asserts
    # the vectors match.
    session = ort.InferenceSession(MODEL_OUT.read_bytes(), providers=["CPUExecutionProvider"])
    parity_texts = {
        "shipping_a": "Your order 4242 has shipped and is on its way to Seattle.",
        "billing_a": "Your invoice 7777 of 49 dollars is now available to download.",
        "security_a": "Security alert: a new sign in to your account from London was detected.",
        "empty": "",
        "punctuation_only": "!!! ??? ... ***",
    }

    def embed(text: str) -> np.ndarray:
        vec = np.array([hashed_vector(text)], dtype=np.float32)
        out = session.run(None, {"input": vec})[0]
        return out[0]

    cases = [{"name": name, "text": text, "embedding": [float(v) for v in embed(text)]}
             for name, text in parity_texts.items()]
    PARITY_OUT.parent.mkdir(parents=True, exist_ok=True)
    PARITY_OUT.write_text(json.dumps(cases, indent=2) + "\n")
    print(f"wrote {PARITY_OUT.relative_to(REPO_ROOT)} ({len(cases)} cases)")

    # Sanity benchmark for picking robust thresholds in the Java similarity test.
    near_a = "Your order 1234 has shipped and is on its way to Austin."
    near_b = "Your order 5678 has shipped and is on its way to Berlin."  # same topic, reworded
    far = "Limited time offer: take 40 percent off your next order today."  # promo topic
    ea, eb, ef = embed(near_a), embed(near_b), embed(far)
    print(f"near-duplicate cosine (shipping vs shipping): {cosine(ea, eb):.3f}")
    print(f"cross-topic cosine   (shipping vs promo):     {cosine(ea, ef):.3f}")
    print(f"identical-text cosine:                        {cosine(ea, ea):.3f}")


if __name__ == "__main__":
    main()
