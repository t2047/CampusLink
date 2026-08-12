"""结构化、可解释的候选重排，融合文本/图片 Embedding 与规则相似度。"""

import base64
import math
import re
import struct
from datetime import date
from difflib import SequenceMatcher
from typing import Any

from .models import MatchResult

WEIGHTS = {
    "text": 0.25,
    "visual": 0.20,
    "cross_modal": 0.10,
    "category": 0.20,
    "location": 0.10,
    "temporal": 0.10,
    "colour": 0.05,
}

DEFAULT_CALIBRATION = {
    "text": (0.65, 0.95),
    "visual": (0.50, 0.95),
    "cross_modal": (0.15, 0.40),
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
        score, reasons, breakdown, mode = _score_candidate_detailed(
            query, candidate, language, text_embedding=text_embedding
        )
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
                score_breakdown=breakdown,
                matching_mode=mode,
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
    score, reasons, _breakdown, _mode = _score_candidate_detailed(
        query, candidate, language, text_embedding=text_embedding
    )
    return score, reasons


def _score_candidate_detailed(
    query: dict[str, Any],
    candidate: dict[str, Any],
    language: str,
    *,
    text_embedding: bool = True,
) -> tuple[float, list[str], dict[str, float], str]:
    components: list[tuple[str, float, float]] = []
    query_text = " ".join(
        str(query.get(field, "")) for field in ("item_name", "keyword", "description")
    ).strip()
    candidate_text = " ".join(
        str(candidate.get(field, "")) for field in ("itemName", "description")
    ).strip()
    pretrained_text = _embedding_similarity(
        query.get("semantic_text_embedding"), candidate.get("semanticTextEmbedding")
    )
    if pretrained_text is not None:
        components.append(
            (
                "text",
                WEIGHTS["text"],
                _calibrate(pretrained_text, query, "text"),
            )
        )
    elif query_text:
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
                "temporal",
                WEIGHTS["temporal"],
                temporal_similarity(
                    str(event_date),
                    str(candidate.get("eventDate", "")),
                    str(query.get("time_description") or query.get("timeDescription") or ""),
                    str(candidate.get("timeDescription") or ""),
                ),
            )
        )
    pretrained_visual = _pretrained_visual_similarity(query, candidate)
    if pretrained_visual is not None:
        components.append(
            ("visual", WEIGHTS["visual"], _calibrate(pretrained_visual, query, "visual"))
        )
    else:
        visual_value = _visual_similarity(query, candidate)
        if visual_value is not None:
            # 颜色直方图只是降级信号，保持旧版 10% 权重，避免同色错误候选压过文本。
            components.append(("visual", 0.10, visual_value))
    cross_modal = _cross_modal_similarity(query, candidate)
    if cross_modal is not None:
        components.append(
            (
                "cross_modal",
                WEIGHTS["cross_modal"],
                _calibrate(cross_modal, query, "cross_modal"),
            )
        )
    if not components:
        return 0.0, [], {}, "baseline"

    active_weight = sum(weight for _, weight, _ in components)
    score = sum(weight * value for _, weight, value in components) / active_weight
    reasons = [reason(name, value, language) for name, _, value in components if value >= 0.6]
    if not reasons:
        reasons = ["综合条件较为接近" if language == "zh" else "Overall conditions are similar"]
    breakdown = {name: round(value, 4) for name, _weight, value in components}
    has_text = pretrained_text is not None
    has_visual = pretrained_visual is not None
    has_cross = cross_modal is not None
    if has_cross:
        mode = "pretrained_multimodal"
    elif has_visual:
        mode = "pretrained_image"
    elif has_text:
        mode = "pretrained_text"
    else:
        mode = "baseline"
    return score, reasons, breakdown, mode


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


def _pretrained_visual_similarity(query: dict[str, Any], candidate: dict[str, Any]) -> float | None:
    return _best_embedding_pair(
        _string_list(query.get("visual_embeddings")),
        _string_list(candidate.get("visualEmbeddings")),
    )


def _cross_modal_similarity(query: dict[str, Any], candidate: dict[str, Any]) -> float | None:
    values: list[float] = []
    query_cross = query.get("cross_modal_text_embedding")
    candidate_cross = candidate.get("crossModalTextEmbedding")
    candidate_images = _string_list(candidate.get("visualEmbeddings"))
    query_images = _string_list(query.get("visual_embeddings"))
    if isinstance(query_cross, str):
        for image in candidate_images:
            value = _embedding_similarity(query_cross, image)
            if value is not None:
                values.append(value)
    if isinstance(candidate_cross, str):
        for image in query_images:
            value = _embedding_similarity(candidate_cross, image)
            if value is not None:
                values.append(value)
    return max(values) if values else None


def _best_embedding_pair(left: list[str], right: list[str]) -> float | None:
    values = [
        value
        for first in left
        for second in right
        if (value := _embedding_similarity(first, second)) is not None
    ]
    return max(values) if values else None


def _embedding_similarity(left: Any, right: Any) -> float | None:
    if not isinstance(left, str) or not isinstance(right, str):
        return None
    left_vector = _decode_vector(left)
    right_vector = _decode_vector(right)
    if left_vector is None or right_vector is None or len(left_vector) != len(right_vector):
        return None
    return max(-1.0, min(1.0, sum(a * b for a, b in zip(left_vector, right_vector, strict=True))))


def _decode_vector(value: str) -> list[float] | None:
    if len(value) > 12_000:
        return None
    try:
        payload = base64.b64decode(value, validate=True)
        if not payload or len(payload) > 8192 or len(payload) % 4 != 0:
            return None
        vector = list(struct.unpack(f"<{len(payload) // 4}f", payload))
        return vector if all(math.isfinite(component) for component in vector) else None
    except (ValueError, struct.error):
        return None


def _string_list(value: Any) -> list[str]:
    return [item for item in value if isinstance(item, str)] if isinstance(value, list) else []


def _calibrate(value: float, query: dict[str, Any], component: str) -> float:
    calibration = query.get("_calibration")
    limits = calibration.get(component) if isinstance(calibration, dict) else None
    if not isinstance(limits, (list, tuple)) or len(limits) != 2:
        limits = DEFAULT_CALIBRATION[component]
    lower, upper = float(limits[0]), float(limits[1])
    if upper <= lower:
        lower, upper = DEFAULT_CALIBRATION[component]
    return max(0.0, min(1.0, (value - lower) / (upper - lower)))


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


def temporal_similarity(left_date: str, right_date: str, left_time: str, right_time: str) -> float:
    date_score = date_similarity(left_date, right_date)
    first = parse_time_minutes(left_time)
    second = parse_time_minutes(right_time)
    if first is None or second is None:
        return date_score
    difference = abs(first - second)
    difference = min(difference, 24 * 60 - difference)
    time_score = max(0.0, 1.0 - difference / 720.0)
    return 0.8 * date_score + 0.2 * time_score


def parse_time_minutes(value: str) -> int | None:
    normalized = value.lower().strip()
    match = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", normalized)
    if match:
        return int(match.group(1)) * 60 + int(match.group(2))
    periods = {
        "凌晨": 3 * 60,
        "早上": 8 * 60,
        "上午": 10 * 60,
        "中午": 12 * 60,
        "下午": 15 * 60,
        "傍晚": 18 * 60,
        "晚上": 21 * 60,
        "morning": 9 * 60,
        "noon": 12 * 60,
        "afternoon": 15 * 60,
        "evening": 19 * 60,
        "night": 22 * 60,
    }
    return next((minutes for label, minutes in periods.items() if label in normalized), None)


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
            "temporal": "日期和时间接近",
            "visual": "图片特征相似",
            "cross_modal": "文字描述与图片相符",
        },
        "en": {
            "text": "Similar text description",
            "category": "Same item category",
            "colour": "Similar colour",
            "location": "Nearby location",
            "temporal": "Close date and time",
            "visual": "Similar image content",
            "cross_modal": "Text matches the image",
        },
    }
    label = labels["zh" if language == "zh" else "en"][component]
    return label if value >= 0.85 else f"{label} ({round(value * 100)}%)"
