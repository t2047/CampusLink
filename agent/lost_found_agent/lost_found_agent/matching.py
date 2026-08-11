"""无 Embedding 阶段的结构化、可解释候选重排。"""

import re
from datetime import date
from difflib import SequenceMatcher
from typing import Any

from .models import MatchResult

WEIGHTS = {
    "text": 0.30,
    "category": 0.30,
    "colour": 0.15,
    "location": 0.15,
    "date": 0.10,
}


def rank_candidates(
    query: dict[str, Any],
    candidates: list[dict[str, Any]],
    minimum_score: float,
    language: str,
) -> list[MatchResult]:
    results: list[MatchResult] = []
    for candidate in candidates:
        score, reasons = score_candidate(query, candidate, language)
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
                time_description=(
                    str(candidate["timeDescription"])
                    if candidate.get("timeDescription")
                    else None
                ),
                image_urls=[str(url) for url in candidate.get("imageUrls", []) if url],
                status=str(candidate.get("status", "OPEN")),
                match_score=round(score, 4),
                match_reason=reasons,
            )
        )
    return sorted(results, key=lambda result: result.match_score, reverse=True)[:5]


def score_candidate(
    query: dict[str, Any], candidate: dict[str, Any], language: str
) -> tuple[float, list[str]]:
    components: list[tuple[str, float, float]] = []
    query_text = " ".join(
        str(query.get(field, "")) for field in ("item_name", "keyword", "description")
    ).strip()
    candidate_text = " ".join(
        str(candidate.get(field, "")) for field in ("itemName", "description")
    ).strip()
    if query_text:
        components.append(("text", WEIGHTS["text"], text_similarity(query_text, candidate_text)))
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
    if not components:
        return 0.0, []

    active_weight = sum(weight for _, weight, _ in components)
    score = sum(weight * value for _, weight, value in components) / active_weight
    reasons = [reason(name, value, language) for name, _, value in components if value >= 0.6]
    if not reasons:
        reasons = ["综合条件较为接近" if language == "zh" else "Overall conditions are similar"]
    return score, reasons


def text_similarity(left: str, right: str) -> float:
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
    return max(sequence, jaccard, containment)


def short_text_similarity(left: str, right: str) -> float:
    left_normalized = normalize(left)
    right_normalized = normalize(right)
    if not left_normalized or not right_normalized:
        return 0.0
    if left_normalized in right_normalized or right_normalized in left_normalized:
        return 1.0
    return SequenceMatcher(None, left_normalized, right_normalized).ratio()


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
        },
        "en": {
            "text": "Similar text description",
            "category": "Same item category",
            "colour": "Similar colour",
            "location": "Nearby location",
            "date": "Close date",
        },
    }
    label = labels["zh" if language == "zh" else "en"][component]
    return label if value >= 0.85 else f"{label} ({round(value * 100)}%)"
