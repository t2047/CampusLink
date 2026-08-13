import base64
import struct

from lost_found_agent.embeddings import embed_image, visual_fingerprint
from lost_found_agent.matching import rank_candidates, score_candidate

from .helpers import make_solid_png


def make_visual(query: tuple[int, int, int]) -> str:
    return visual_fingerprint(embed_image(make_solid_png(query)))


def make_embedding(*values: float) -> str:
    return base64.b64encode(struct.pack(f"<{len(values)}f", *values)).decode("ascii")


def test_missing_fields_are_removed_from_weight_denominator() -> None:
    score, reasons = score_candidate(
        {"category": "UMBRELLA"},
        {"category": "UMBRELLA"},
        "en",
    )

    assert score == 1.0
    assert reasons == ["Same item category"]


def test_threshold_filters_candidates_and_result_is_limited_to_five() -> None:
    query = {"category": "BAG", "colour": "blue"}
    candidates = [
        {
            "id": index,
            "itemName": f"Bag {index}",
            "category": "BAG",
            "description": "A bag",
            "colour": "Blue",
            "location": "Library",
            "eventDate": "2026-08-08",
            "status": "OPEN",
        }
        for index in range(10)
    ]
    candidates.append(
        {
            "id": 99,
            "itemName": "Wallet",
            "category": "WALLET_PURSE",
            "description": "Brown wallet",
            "colour": "Brown",
            "location": "Gym",
            "eventDate": "2026-01-01",
            "status": "OPEN",
        }
    )

    results = rank_candidates(query, candidates, 0.35, "en")

    assert len(results) == 5
    assert all(result.category == "BAG" for result in results)


def test_visual_component_discriminates_identical_text_candidates() -> None:
    query = {"item_name": "水杯", "visual_fingerprint": make_visual((0, 0, 255))}
    candidates = [
        {
            "id": 1,
            "itemName": "水杯",
            "category": "OTHER",
            "description": "一个杯子",
            "location": "图书馆",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualFingerprints": [make_visual((0, 0, 255))],
        },
        {
            "id": 2,
            "itemName": "水杯",
            "category": "OTHER",
            "description": "一个杯子",
            "location": "图书馆",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualFingerprints": [make_visual((255, 0, 0))],
        },
    ]

    results = rank_candidates(query, candidates, 0.0, "zh")

    assert results[0].item_id == "1"
    assert any("图片特征相似" in reason for reason in results[0].match_reason)


def test_visual_component_uses_best_candidate_image() -> None:
    query = {"visual_fingerprint": make_visual((0, 0, 255))}
    base = {
        "id": 7,
        "itemName": "水杯",
        "category": "OTHER",
        "description": "一个杯子",
        "location": "图书馆",
        "eventDate": "2026-08-08",
        "status": "OPEN",
    }
    with_matching_image = {
        **base,
        "visualFingerprints": [make_visual((255, 0, 0)), make_visual((0, 0, 255))],
    }
    only_wrong_image = {**base, "visualFingerprints": [make_visual((255, 0, 0))]}

    better_score, _ = score_candidate(query, with_matching_image, "zh")
    worse_score, _ = score_candidate(query, only_wrong_image, "zh")

    assert better_score == 1.0
    assert worse_score == 0.0


def test_query_supports_multiple_fingerprints_and_takes_best_pair() -> None:
    query = {"visual_fingerprints": [make_visual((255, 0, 0)), make_visual((0, 0, 255))]}
    base = {
        "id": 7,
        "itemName": "水杯",
        "category": "OTHER",
        "description": "一个杯子",
        "location": "图书馆",
        "eventDate": "2026-08-08",
        "status": "OPEN",
    }
    matches_second = {**base, "visualFingerprints": [make_visual((0, 0, 255))]}
    no_match = {**base, "visualFingerprints": [make_visual((255, 255, 0))]}

    better_score, _ = score_candidate(query, matches_second, "zh")
    worse_score, _ = score_candidate(query, no_match, "zh")

    assert better_score == 1.0
    assert worse_score == 0.0


def test_text_embedding_flag_turns_off_vector_signal() -> None:
    query = {"description": "black wireless earbuds in charging case"}
    candidate = {
        "id": 1,
        "itemName": "Black Bluetooth earphones",
        "category": "ELECTRONICS",
        "description": "Small black earphones with a charging case",
        "location": "Library",
        "eventDate": "2026-08-08",
        "status": "OPEN",
    }

    with_embedding, _ = score_candidate(query, candidate, "en")
    rule_only, _ = score_candidate(query, candidate, "en", text_embedding=False)

    assert with_embedding >= rule_only


def test_pretrained_image_embedding_uses_best_image_pair_and_reports_mode() -> None:
    query = {
        "visual_embeddings": [make_embedding(0.0, 1.0), make_embedding(1.0, 0.0)],
    }
    candidates = [
        {
            "id": 1,
            "reportType": "FOUND",
            "itemName": "Black headphones",
            "category": "ELECTRONICS",
            "description": "Headphones in a case",
            "location": "Library",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualEmbeddings": [make_embedding(1.0, 0.0)],
        },
        {
            "id": 2,
            "reportType": "FOUND",
            "itemName": "Red umbrella",
            "category": "UMBRELLA",
            "description": "Foldable umbrella",
            "location": "Gym",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualEmbeddings": [make_embedding(0.0, -1.0)],
        },
    ]

    results = rank_candidates(query, candidates, 0.0, "en")

    assert results[0].item_id == "1"
    assert results[0].matching_mode == "pretrained_image"
    assert results[0].score_breakdown["visual"] == 1.0
    assert "Similar image content" in results[0].match_reason


def test_pretrained_text_and_cross_modal_vectors_are_calibrated() -> None:
    same = make_embedding(1.0, 0.0)
    query = {
        "semantic_text_embedding": same,
        "cross_modal_text_embedding": same,
    }
    candidate = {
        "id": 3,
        "reportType": "FOUND",
        "itemName": "黑色耳机",
        "category": "ELECTRONICS",
        "description": "装在充电盒中的耳机",
        "location": "图书馆",
        "eventDate": "2026-08-08",
        "status": "OPEN",
        "semanticTextEmbedding": same,
        "visualEmbeddings": [same],
    }

    result = rank_candidates(query, [candidate], 0.0, "zh")[0]

    assert result.matching_mode == "pretrained_multimodal"
    assert result.score_breakdown == {"text": 1.0, "cross_modal": 1.0}
    assert "文字描述相似" in result.match_reason
    assert "文字描述与图片相符" in result.match_reason


def test_invalid_pretrained_vector_falls_back_to_rules() -> None:
    results = rank_candidates(
        {"item_name": "黑色耳机", "semantic_text_embedding": "not-base64"},
        [
            {
                "id": 4,
                "reportType": "FOUND",
                "itemName": "黑色耳机",
                "category": "ELECTRONICS",
                "description": "一副耳机",
                "location": "图书馆",
                "eventDate": "2026-08-08",
                "status": "OPEN",
                "semanticTextEmbedding": make_embedding(1.0, 0.0),
            }
        ],
        0.0,
        "zh",
    )

    assert results[0].matching_mode == "baseline"
    assert results[0].score_breakdown["text"] > 0.0
