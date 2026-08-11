"""结构化、可解释的候选重排，融合文本/图片 Embedding 与规则相似度。"""

import re
from datetime import date
from difflib import SequenceMatcher
from typing import Any

from .models import MatchResult

WEIGHTS = {
    "text": 0.28,
    "category": 0.28,
    "colour": 0.14,
    "location": 0.14,
    "date": 0.06,
    "visual": 0.10,
}


def rank_candidates(
    query: dict[str, Any],
    candidates: list[dict[str, Any]],
    minimum_score: float,
    language: str,
    *,
    text_embedding: bool = True,
) -> list[MatchResult]:
    results: list[MatchResult] = []
    for candidate in candidates:
        score, reasons = score_candidate(query, candidate, language, text_embedding=text_embedding)
        if score < minimum_score:
            continue
        results.append(
            MatchResult(
                item_id=str(candidate["id"]),
                report_type=str(candidate.get("reportType", "FOUND")),
                item_name=str(candidate.get("itemName", "")),
                category=str(candidate.get("category", "OTHER")),
                description=str(candidate.get("description", "")),
                colour=(str(candidate["colour"]) if candidate.get("colour") else None),
                location=str(candidate.get("location", "")),
                event_date=str(candidate.get("eventDate", "")),
                time_description=str(candidate["timeDescription"])
                if candidate.get("timeDescription")
                else None,
                image_urls=[str(url) for url in candidate.get("imageUrls", []) if url],
                status=str(candidate.get("status", "OPEN")),
                match_score=round(score, 4),
                match_reason=reasons,
            )
        )
    return sorted(results, key=lambda result: result.match_score, reverse=True)[:5]


def score_candidate(
    query: dict[str, Any],
    candidate: dict[str, Any],
    language: str,
    *,
    text_embedding: bool = True,
) -> tuple[float, list[str]]:
    components: list[tuple[str, float, float]] = []
    query_text = " ".join(
        str(query.get(field, "")) for field in ("item_name", "keyword", "description")
    ).strip()
    candidate_text = " ".join(
        str(candidate.get(field, "")) for field in ("itemName", "description")
    ).strip()
    if query_text:
        components.append(
            (
                "text",
                WEIGHTS["text"],
                text_similarity(query_text, candidate_text, text_embedding=text_embedding),
            )
        )
    if query.get("category"):
        components.append(
            (
                "category",
                WEIGHTS["category"],
                float(str(query["category"]) == str(candidate.get("category"))),
            )
        )
    if query.get("colour"):
        components.append(
            (
                "colour",
                WEIGHTS["colour"],
                short_text_similarity(str(query["colour"]), str(candidate.get("colour", ""))),
            )
        )
    if query.get("location"):
        components.append(
            (
                "location",
                WEIGHTS["location"],
                short_text_similarity(str(query["location"]), str(candidate.get("location", ""))),
            )
        )
    event_date = query.get("event_date") or query.get("date")
    if event_date:
        components.append(
            (
                "date",
                WEIGHTS["date"],
                date_similarity(str(event_date), str(candidate.get("eventDate", ""))),
            )
        )
    visual_value = _visual_similarity(query, candidate)
    if visual_value is not None:
        components.append(("visual", WEIGHTS["visual"], visual_value))
    if not components:
        return 0.0, []

    active_weight = sum(weight for _, weight, _ in components)
    score = sum(weight * value for _, weight, value in components) / active_weight
    reasons = [reason(name, value, language) for name, _, value in components if value >= 0.6]
    if not reasons:
        reasons = ["综合条件较为接近" if language == "zh" else "Overall conditions are similar"]
    return score, reasons


def text_similarity(left: str, right: str, *, text_embedding: bool = True) -> float:
    left_normalized = normalize(left)
    right_normalized = normalize(right)
    if not left_normalized or not right_normalized:
        return 0.0
    sequence = SequenceMatcher(None, left_normalized, right_normalized).ratio()
    left_tokens = tokens(left_normalized)
    right_tokens = tokens(right_normalized)
    union = left_tokens | right_tokens
    jaccard = len(left_tokens & right_tokens) / len(union) if union else 0.0
    containment = (
        min(len(left_tokens), len(right_tokens)) / max(len(left_tokens), len(right_tokens))
        if left_normalized in right_normalized or right_normalized in left_normalized
        else 0.0
    )
    vector = 0.0
    if text_embedding:
        from .embeddings import embedding_similarity

        vector = embedding_similarity(left_normalized, right_normalized)
    return max(sequence, jaccard, containment, vector)


def short_text_similarity(left: str, right: str) -> float:
    left_normalized = normalize(left)
    right_normalized = normalize(right)
    if not left_normalized or not right_normalized:
        return 0.0
    if left_normalized in right_normalized or right_normalized in left_normalized:
        return 1.0
    return SequenceMatcher(None, left_normalized, right_normalized).ratio()


def _visual_similarity(query: dict[str, Any], candidate: dict[str, Any]) -> float | None:
    query_fingerprints = _query_fingerprints(query)
    candidate_fingerprints = candidate.get("visualFingerprints")
    if not query_fingerprints or not isinstance(candidate_fingerprints, list):
        return None
    from .embeddings import visual_similarity

    best: float | None = None
    for query_fingerprint in query_fingerprints:
        for fingerprint in candidate_fingerprints:
            if not isinstance(fingerprint, str):
                continue
            value = visual_similarity(query_fingerprint, fingerprint)
            if value is not None and (best is None or value > best):
                best = value
    return best


def _query_fingerprints(query: dict[str, Any]) -> list[str]:
    """查询端视觉指纹：支持单图（visual_fingerprint）与多图（visual_fingerprints）两种写法。"""
    single = query.get("visual_fingerprint")
    if isinstance(single, str):
        return [single]
    multiple = query.get("visual_fingerprints")
    if isinstance(multiple, list):
        return [value for value in multiple if isinstance(value, str)]
    return []


def date_similarity(left: str, right: str) -> float:
    try:
        days = abs((date.fromisoformat(left) - date.fromisoformat(right)).days)
    except ValueError:
        return 0.0
    if days == 0:
        return 1.0
    if days <= 1:
        return 0.9
    if days <= 3:
        return 0.7
    if days <= 7:
        return 0.5
    if days <= 30:
        return 0.2
    return 0.0


def normalize(value: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]+", " ", value.lower()).strip()


def tokens(value: str) -> set[str]:
    words = set(value.split())
    compact = value.replace(" ", "")
    words.update(compact[index : index + 2] for index in range(max(0, len(compact) - 1)))
    return {word for word in words if word}


def reason(component: str, value: float, language: str) -> str:
    labels = {
        "zh": {
            "text": "文字描述相似",
            "category": "物品类别一致",
            "colour": "颜色相似",
            "location": "地点接近",
            "date": "日期接近",
            "visual": "图片特征相似",
        },
        "en": {
            "text": "Similar text description",
            "category": "Same item category",
            "colour": "Similar colour",
            "location": "Nearby location",
            "date": "Close date",
            "visual": "Similar image features",
        },
    }
    label = labels["zh" if language == "zh" else "en"][component]
    return label if value >= 0.85 else f"{label} ({round(value * 100)}%)"
