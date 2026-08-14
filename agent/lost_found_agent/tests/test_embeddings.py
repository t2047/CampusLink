import base64

from lost_found_agent.embeddings import (
    embed_image,
    embedding_similarity,
    image_similarity,
    visual_fingerprint,
    visual_fingerprint_to_vector,
    visual_similarity,
)
from lost_found_agent.matching import rank_candidates

# Canonical image shared with the Java VisualFingerprintExtractorTest:
# a 16x16 RGB PNG, left half blue (0,0,255), right half red (255,0,0),
# with no gAMA/iCCP/alpha so both decoders see the same pixels.
GOLDEN_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAKElEQVR4nGNkYPjPgA38Z2DEKs7EQCJgGtVABGAiRhEyGNVADCA5lAA1WwIfpDdLxAAAAABJRU5ErkJggg=="
)
# Computed with embed_image -> visual_fingerprint; the Java extractor must
# reproduce this exact string on the same PNG (cross-language parity contract).
GOLDEN_FINGERPRINT = (
    "VF1:AAAAAAAAAAAAAAAAAAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
)


def test_embedding_similarity_supports_chinese_partial_overlap() -> None:
    assert embedding_similarity("黑色无线耳机", "黑色耳机盒") > embedding_similarity(
        "黑色无线耳机", "红色雨伞"
    )


def test_rank_candidates_uses_vector_signal_for_text_recall() -> None:
    results = rank_candidates(
        {"item_name": "wireless earbuds", "description": "black wireless earbuds"},
        [
            {
                "id": 1,
                "itemName": "Black Bluetooth earbuds",
                "category": "ELECTRONICS",
                "description": "Small black earphones in a charging case",
                "location": "Library",
                "eventDate": "2026-08-08",
                "status": "OPEN",
            },
            {
                "id": 2,
                "itemName": "Red umbrella",
                "category": "UMBRELLA",
                "description": "Compact umbrella",
                "location": "Gym",
                "eventDate": "2026-08-08",
                "status": "OPEN",
            },
        ],
        0.2,
        "en",
    )

    assert results[0].item_id == "1"


def test_embed_image_and_visual_fingerprint_match_java_golden() -> None:
    fingerprint = visual_fingerprint(embed_image(GOLDEN_PNG))

    assert len(fingerprint) == 348
    assert fingerprint.startswith("VF1:")
    assert fingerprint == GOLDEN_FINGERPRINT


def test_visual_fingerprint_round_trip() -> None:
    vector = embed_image(GOLDEN_PNG)
    restored = visual_fingerprint_to_vector(visual_fingerprint(vector))

    assert restored is not None
    assert restored == vector


def test_visual_fingerprint_to_vector_rejects_malformed_values() -> None:
    assert visual_fingerprint_to_vector("not-a-fingerprint") is None
    assert visual_fingerprint_to_vector("VF1:not-base64!!!") is None
    assert visual_fingerprint_to_vector("VF1:AQID") is None  # truncated payload


def test_image_similarity_is_one_for_identical_visuals() -> None:
    vector = embed_image(GOLDEN_PNG)

    assert image_similarity(vector, vector) == 1.0


def test_visual_similarity_distinguishes_solid_colours() -> None:
    blue_fingerprint = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    red_fingerprint = visual_fingerprint(embed_image(make_solid_png((255, 0, 0))))

    differing = visual_similarity(blue_fingerprint, red_fingerprint)
    assert differing is not None
    assert differing < 0.5
    assert visual_similarity(blue_fingerprint, blue_fingerprint) == 1.0


def test_visual_similarity_is_none_on_malformed_input() -> None:
    assert visual_similarity("garbage", "VF1:garbage") is None


def test_embed_image_fallback_is_deterministic_for_undecodable_bytes() -> None:
    first = visual_fingerprint(embed_image(b"\x00\x01\x02 not an image"))
    second = visual_fingerprint(embed_image(b"\x00\x01\x02 not an image"))

    assert first == second
    assert first.startswith("VF1:")


def make_solid_png(rgb: tuple[int, int, int]) -> bytes:
    import io

    from PIL import Image

    image = Image.new("RGB", (16, 16), rgb)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()
