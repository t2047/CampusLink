"""手动真实模型 smoke；普通 PR CI 不设置开关，因此不会下载模型权重。"""

import io
import os
import struct

import pytest
from PIL import Image, ImageDraw

from lost_found_embedding.config import Settings
from lost_found_embedding.models import TextEmbeddingItem
from lost_found_embedding.runtime import SentenceTransformerRuntime

pytestmark = pytest.mark.skipif(
    os.getenv("RUN_REAL_MULTIMODAL_TESTS") != "1",
    reason="仅在手动真实模型 workflow 中执行",
)


def cosine(left: list[float], right: list[float]) -> float:
    return sum(first * second for first, second in zip(left, right, strict=True))


def decode(value: str) -> list[float]:
    import base64

    payload = base64.b64decode(value)
    return list(struct.unpack(f"<{len(payload) // 4}f", payload))


def icon(kind: str) -> bytes:
    output = io.BytesIO()
    image = Image.new("RGB", (224, 224), "white")
    draw = ImageDraw.Draw(image)
    if kind == "umbrella":
        draw.pieslice((30, 25, 194, 175), 180, 360, fill="red")
        draw.line((112, 100, 112, 190), fill="black", width=10)
        draw.arc((82, 160, 122, 205), 0, 180, fill="black", width=8)
    else:
        draw.arc((35, 30, 189, 185), 180, 360, fill="black", width=18)
        draw.rounded_rectangle((25, 105, 75, 190), 12, fill="blue")
        draw.rounded_rectangle((149, 105, 199, 190), 12, fill="blue")
    image.save(output, format="PNG")
    return output.getvalue()


def test_real_e5_and_clip_are_repeatable_and_discriminative() -> None:
    runtime = SentenceTransformerRuntime(
        Settings(shared_secret="manual-smoke-secret", cross_modal_enabled="off")
    )
    text_items = [
        TextEmbeddingItem(id="q", text="我在图书馆丢了一副黑色无线耳机", role="query"),
        TextEmbeddingItem(id="p", text="Black wireless earbuds lost in the library"),
        TextEmbeddingItem(id="n", text="A red umbrella found near the swimming pool"),
    ]
    first = runtime.encode_text(text_items, ["semantic"])
    second = runtime.encode_text(text_items, ["semantic"])
    query = decode(first[0]["semantic"].vector)  # type: ignore[union-attr]
    positive = decode(first[1]["semantic"].vector)  # type: ignore[union-attr]
    negative = decode(first[2]["semantic"].vector)  # type: ignore[union-attr]
    assert cosine(query, positive) > cosine(query, negative)
    assert first[0]["semantic"] == second[0]["semantic"]

    image_vectors = runtime.encode_images([icon("umbrella"), icon("umbrella"), icon("headphones")])
    umbrella = decode(image_vectors[0].vector)
    same_umbrella = decode(image_vectors[1].vector)
    headphones = decode(image_vectors[2].vector)
    assert cosine(umbrella, same_umbrella) > cosine(umbrella, headphones)
    assert image_vectors[0].dimension == 512


def test_real_multilingual_clip_matches_chinese_text_to_image() -> None:
    runtime = SentenceTransformerRuntime(
        Settings(shared_secret="manual-smoke-secret", cross_modal_enabled="on")
    )
    text = runtime.encode_text(
        [TextEmbeddingItem(id="q", text="一把红色雨伞", role="query")],
        ["cross_modal"],
    )[0]["cross_modal"]
    assert text is not None
    text_vector = decode(text.vector)
    images = runtime.encode_images([icon("umbrella"), icon("headphones")])
    umbrella_score = cosine(text_vector, decode(images[0].vector))
    headphones_score = cosine(text_vector, decode(images[1].vector))
    assert umbrella_score > headphones_score
