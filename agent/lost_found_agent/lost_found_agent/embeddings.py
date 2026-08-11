"""Deterministic local embeddings for multilingual lost-and-found matching.

This is an offline baseline: it gives the matching pipeline a vector-recall
surface and reproducible Recall@K tests before a production embedding service
or vector database is introduced.

Text vectors and image fingerprints are both deterministic so they can be
recomputed identically in CI and in the Java backend.
"""

import base64
import hashlib
import io
import struct
from collections.abc import Iterable
from hashlib import blake2b
from math import sqrt

from .matching import normalize, tokens

EMBEDDING_DIMENSIONS = 128

VISUAL_BUCKETS = 64
VISUAL_GRID_SIZE = 8
VISUAL_FINGERPRINT_PREFIX = "VF1:"
_VISUAL_FLOAT_FORMAT = "<64f"


def embed_text(value: str, dimensions: int = EMBEDDING_DIMENSIONS) -> list[float]:
    vector = [0.0] * dimensions
    for token in embedding_tokens(value):
        digest = blake2b(token.encode("utf-8"), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimensions
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[bucket] += sign
    norm = sqrt(sum(component * component for component in vector))
    if norm == 0:
        return vector
    return [component / norm for component in vector]


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if len(left) != len(right):
        raise ValueError("embedding dimensions must match")
    return max(0.0, min(1.0, sum(a * b for a, b in zip(left, right, strict=True))))


def embedding_similarity(left: str, right: str) -> float:
    left_vector = embed_text(left)
    right_vector = embed_text(right)
    if not any(left_vector) or not any(right_vector):
        return 0.0
    return cosine_similarity(left_vector, right_vector)


def embedding_tokens(value: str) -> Iterable[str]:
    normalized = normalize(value)
    base_tokens = tokens(normalized)
    compact = normalized.replace(" ", "")
    char_grams = {
        compact[index : index + size]
        for size in (1, 2, 3)
        for index in range(max(0, len(compact) - size + 1))
    }
    return {token for token in base_tokens | char_grams if token}


def embed_image(image_data: bytes) -> list[float]:
    """Return a deterministic 64-dim colour-histogram vector for an image.

    The spec is shared with the Java backend (VisualFingerprintExtractor):
    sample an 8x8 grid with integer scaling, quantize each RGB pixel into a
    64-bucket histogram, then L1-normalize. WebP (which the JDK ImageIO cannot
    decode) and undecodable bytes fall back to a SHA-256 histogram so both
    sides agree.
    """
    if not _is_webp(image_data):
        counts = _colour_histogram(image_data)
        if counts is not None:
            return _normalize_visual(counts)
    return _fallback_visual_vector(image_data)


def image_similarity(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or not any(left) or not any(right):
        return 0.0
    distance = sum(abs(a - b) for a, b in zip(left, right, strict=True))
    return max(0.0, min(1.0, 1.0 - distance / 2.0))


def visual_fingerprint(vector: list[float]) -> str:
    if len(vector) != VISUAL_BUCKETS:
        raise ValueError("visual vector must have exactly 64 dimensions")
    payload = struct.pack(_VISUAL_FLOAT_FORMAT, *vector)
    return VISUAL_FINGERPRINT_PREFIX + base64.b64encode(payload).decode("ascii")


def visual_fingerprint_to_vector(fingerprint: str) -> list[float] | None:
    if not fingerprint.startswith(VISUAL_FINGERPRINT_PREFIX):
        return None
    try:
        payload = base64.b64decode(fingerprint[len(VISUAL_FINGERPRINT_PREFIX) :], validate=True)
        return list(struct.unpack(_VISUAL_FLOAT_FORMAT, payload))
    except (ValueError, struct.error):
        return None


def visual_similarity(left: str, right: str) -> float | None:
    left_vector = visual_fingerprint_to_vector(left)
    right_vector = visual_fingerprint_to_vector(right)
    if left_vector is None or right_vector is None:
        return None
    return image_similarity(left_vector, right_vector)


def _colour_histogram(image_data: bytes) -> list[int] | None:
    image = None
    converted = None
    try:
        from PIL import Image

        image = Image.open(io.BytesIO(image_data))
        converted = image.convert("RGB")
        width, height = converted.size
        raw = converted.tobytes()
    except Exception:
        return None
    finally:
        if converted is not None:
            converted.close()
        if image is not None:
            image.close()

    counts = [0] * VISUAL_BUCKETS
    for row in range(VISUAL_GRID_SIZE):
        sample_y = (row * height) // VISUAL_GRID_SIZE
        row_offset = sample_y * width
        for column in range(VISUAL_GRID_SIZE):
            sample_x = (column * width) // VISUAL_GRID_SIZE
            offset = (row_offset + sample_x) * 3
            bucket = (
                ((raw[offset] >> 6) & 3) << 4
                | ((raw[offset + 1] >> 6) & 3) << 2
                | ((raw[offset + 2] >> 6) & 3)
            )
            counts[bucket] += 1
    return counts


def _fallback_visual_vector(image_data: bytes) -> list[float]:
    sample = image_data[:1024] or image_data
    digest = hashlib.sha256(sample).digest()
    counts = [digest[index % len(digest)] for index in range(VISUAL_BUCKETS)]
    return _normalize_visual(counts)


def _normalize_visual(counts: list[int]) -> list[float]:
    total = sum(counts)
    if total == 0:
        return [0.0] * VISUAL_BUCKETS
    return [count / total for count in counts]


def _is_webp(image_data: bytes) -> bool:
    return len(image_data) >= 12 and image_data[:4] == b"RIFF" and image_data[8:12] == b"WEBP"
