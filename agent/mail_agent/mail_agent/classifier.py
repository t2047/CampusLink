"""Email classification integration for the CampusLink mail service.

Every message mapped to ``MailMessage`` is tagged with one of four categories
(``campus`` / ``career`` / ``finance`` / ``other``). Classification is
**LLM-first**: when an LLM is configured (``MAIL_LLM_*``, falling back to
``DEEPSEEK_*``), it classifies the messages in batches of up to
``_LLM_MAX_MESSAGES`` with a single chat call each; anything the LLM does not
answer falls back to the trained ML model in ``agent/mail_agent/ml``
(``email_classifier.py`` plus ``models/email_classifier.joblib``). Only when
both fail does a message fall back to ``other`` — classification is
best-effort and must never break the mail service.

The strategy is controlled by ``MAIL_CLASSIFIER_MODE``:

  * ``auto`` (default) — LLM first, ML fallback, ``other`` last;
  * ``llm`` — LLM only; LLM failures fall straight to ``other``;
  * ``ml`` — always the trained ML model (LLM disabled).

Predictions are cached per message id for a bounded window, and the ML model
path can be overridden with the ``MAIL_CLASSIFIER_MODEL`` env var.
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
import threading
import time
from pathlib import Path
from typing import Any, Iterable

from . import config

logger = logging.getLogger(__name__)

# ``ml`` lives next to the mail_agent package (agent/mail_agent/ml). Make it
# importable no matter how the service is started (plain uvicorn from the
# mail_agent directory, an editable install, or the Docker image).
if str(config.SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(config.SERVICE_DIR))

MODEL_PATH = Path(
    os.environ.get(
        "MAIL_CLASSIFIER_MODEL",
        str(config.SERVICE_DIR / "ml" / "models" / "email_classifier.joblib"),
    )
)

CATEGORIES = ("campus", "career", "finance", "other")
FALLBACK_CATEGORY = "other"

# LLM classification prompt budget: truncate each email's body and cap the
# batch so a page of mail (the service never exposes more than 50 messages)
# stays a single, cheap LLM round trip.
_LLM_MAX_BODY_CHARS = 1500
_LLM_MAX_MESSAGES = 50
_LLM_TIMEOUT_SECONDS = 30

# Small in-process prediction cache: the same message id (page re-loads, list +
# detail) should not re-run the LLM/model every time.
_CACHE_MAX_ENTRIES = 2000
_CACHE_TTL_SECONDS = 3600

_classifier: Any | None = None
_load_failed: Exception | None = None
_cache: dict[str, tuple[float, str]] = {}
_cache_lock = threading.Lock()


def _classifier_mode() -> str:
    """Resolve the classification strategy (``auto`` | ``llm`` | ``ml``)."""
    mode = os.environ.get("MAIL_CLASSIFIER_MODE", "auto").strip().lower()
    return mode if mode in ("auto", "llm", "ml") else "auto"


def _llm_configured() -> bool:
    return bool(config.MAIL_LLM_API_KEY)


def _cache_get(message_id: str) -> str | None:
    with _cache_lock:
        entry = _cache.get(message_id)
        if entry is None:
            return None
        cached_at, category = entry
        if time.monotonic() - cached_at <= _CACHE_TTL_SECONDS:
            return category
        _cache.pop(message_id, None)
        return None


def _cache_put(message_id: str, category: str) -> None:
    with _cache_lock:
        if len(_cache) >= _CACHE_MAX_ENTRIES:
            _cache.pop(next(iter(_cache)), None)
        _cache[message_id] = (time.monotonic(), category)


def _ensure_classifier() -> Any | None:
    """Load (once) and return the ML classifier, or ``None`` when unavailable."""
    global _classifier, _load_failed
    if _classifier is not None or _load_failed is not None:
        return _classifier
    try:
        from ml.email_classifier import EmailClassifier

        classifier = EmailClassifier(MODEL_PATH)
        classifier.categories  # triggers the model load; raises if unusable
        _classifier = classifier
        logger.info("Email classifier loaded from %s", MODEL_PATH)
    except Exception as exc:  # noqa: BLE001 - model issues must not break mail
        _load_failed = exc
        logger.exception(
            "Email classifier unavailable (%s); mail will fall back to '%s'",
            exc,
            FALLBACK_CATEGORY,
        )
    return _classifier


def _llm_prompt(records: list[dict[str, Any]]) -> str:
    lines = [
        "You are the CampusLink email classifier. Classify each email below into "
        "exactly one of these categories:",
        "- campus: university/campus life, courses, exams, events, clubs, administration",
        "- career: jobs, internships, career fairs, recruiting, networking",
        "- finance: payments, invoices, tuition, scholarships, financial matters",
        "- other: anything else (personal, promotions, social, newsletters)",
        "",
        "Rules:",
        "- Base each decision on the sender, subject and body text.",
        '- Respond with ONLY a JSON array (no markdown, no commentary).',
        '- Every object has exactly two keys: "email_index" (1-based, matching the '
        'numbered emails below) and "category" (one of the four values above).',
        "- Classify EVERY email; never omit or skip one.",
        "",
    ]
    for index, record in enumerate(records, start=1):
        body = " ".join(str(record.get("body") or "").split())[:_LLM_MAX_BODY_CHARS]
        lines.append(f"Email {index}:")
        lines.append(f"Sender: {record.get('sender') or ''}")
        lines.append(f"Subject: {record.get('subject') or ''}")
        lines.append(f"Body: {body}")
        lines.append("")
    lines.append("JSON array:")
    return "\n".join(lines)


def _parse_llm_categories(text: str) -> list[dict[str, Any]]:
    """Parse the LLM's JSON array of ``{email_index, category}`` objects.

    Tolerates ```json`` fences and trailing text; raises when no array is
    present (the caller decides whether to fall back to the ML model).
    """
    cleaned = str(text).strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)
    start = cleaned.find("[")
    end = cleaned.rfind("]")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("LLM did not return a JSON array")
    payload = json.loads(cleaned[start : end + 1])
    if not isinstance(payload, list):
        raise ValueError("LLM response is not a JSON array")
    return [item for item in payload if isinstance(item, dict)]


def _classify_with_llm(records: list[dict[str, Any]]) -> dict[str, str]:
    """Ask the LLM to classify a batch of emails in one call.

    Returns ``{message_id: category}`` for the records the model answered with
    a valid category. Raises when the call/parse fails; the caller decides
    whether to fall back to the ML model.
    """
    from langchain_openai import ChatOpenAI

    llm = ChatOpenAI(
        model=config.MAIL_LLM_MODEL,
        api_key=config.MAIL_LLM_API_KEY,
        base_url=config.MAIL_LLM_BASE_URL,
        temperature=0,
        timeout=_LLM_TIMEOUT_SECONDS,
        max_retries=1,
    )
    response = llm.invoke(_llm_prompt(records))
    content = getattr(response, "content", "") or ""
    if isinstance(content, list):  # multi-part content -> join text parts
        content = " ".join(
            str(part.get("text", ""))
            for part in content
            if isinstance(part, dict) and part.get("type") == "text"
        )
    by_index = {
        index: str(record["message_id"])
        for index, record in enumerate(records, start=1)
    }
    result: dict[str, str] = {}
    for item in _parse_llm_categories(content):
        raw_index = item.get("email_index")
        if raw_index is None:
            continue
        try:
            index = int(raw_index)
        except (TypeError, ValueError):
            continue
        category = str(item.get("category", "")).strip().lower()
        if index in by_index and category in CATEGORIES:
            result[by_index[index]] = category
    return result


def _classify_with_ml(
    message_id: str,
    subject: str | None,
    body: str | None,
    sender: str | None,
    sender_email: str | None,
) -> str | None:
    """ML prediction for one message, or ``None`` on any failure."""
    classifier = _ensure_classifier()
    if classifier is None:
        return None
    try:
        predicted = str(
            classifier.classify(
                subject=subject, body=body, sender=sender, sender_email=sender_email
            ).get("category", "")
        ).lower()
        if predicted in CATEGORIES:
            return predicted
        logger.warning(
            "ML classifier returned unknown category %r for message %s; using %r",
            predicted,
            message_id,
            FALLBACK_CATEGORY,
        )
    except Exception:  # noqa: BLE001
        logger.exception(
            "ML classification failed for message %s; using %s",
            message_id,
            FALLBACK_CATEGORY,
        )
    return None


def _fill_ml_fallback(
    records: list[dict[str, Any]],
    llm_results: dict[str, str],
) -> dict[str, str]:
    """ML-classify every record the LLM did not answer (``auto`` mode)."""
    results = dict(llm_results)
    for record in records:
        if record["message_id"] in results:
            continue
        category = _classify_with_ml(
            record["message_id"],
            record.get("subject"),
            record.get("body"),
            record.get("sender"),
            record.get("sender_email"),
        )
        if category is not None:
            results[record["message_id"]] = category
    return results


def classify(
    message_id: str,
    subject: str | None = "",
    body: str | None = "",
    sender: str | None = "",
    sender_email: str | None = None,
) -> str:
    """Return one of the four mail categories for ``message_id``.

    LLM first, ML fallback, ``other`` last. Predictions are cached per message
    id for a bounded window; any failure falls back without breaking mail.
    """
    cached = _cache_get(message_id)
    if cached is not None:
        return cached

    record = {
        "message_id": message_id,
        "subject": subject or "",
        "body": body or "",
        "sender": sender or "",
        "sender_email": sender_email,
    }
    category = FALLBACK_CATEGORY
    llm_answered = False
    mode = _classifier_mode()
    if mode in ("auto", "llm") and _llm_configured():
        try:
            answered = _classify_with_llm([record])
            if message_id in answered:
                # An LLM answer of ``other`` is a real classification and must
                # not be overridden by the ML fallback below.
                category = answered[message_id]
                llm_answered = True
        except Exception as exc:  # noqa: BLE001 - never break mail
            logger.warning(
                "LLM classification failed for message %s; %s",
                message_id,
                exc,
            )
    if not llm_answered and mode in ("auto", "ml"):
        fallback = _classify_with_ml(message_id, subject, body, sender, sender_email)
        if fallback is not None:
            category = fallback

    _cache_put(message_id, category)
    return category


def classify_many(records: Iterable[dict[str, Any]]) -> dict[str, str]:
    """Classify a batch of emails: one LLM call per chunk, ML fallback.

    ``records``: iterable of dicts with keys ``message_id``, ``subject``,
    ``body``, ``sender`` (``sender_email`` optional). Returns
    ``{message_id: category}`` for **every** input record (cached entries
    included) and populates the same per-message cache as :func:`classify`.
    """
    record_list = list(records)
    if not record_list:
        return {}
    uncached = [
        record
        for record in record_list
        if _cache_get(record["message_id"]) is None
    ]

    results: dict[str, str] = {}
    mode = _classifier_mode()
    if mode in ("auto", "llm") and _llm_configured() and uncached:
        for start in range(0, len(uncached), _LLM_MAX_MESSAGES):
            chunk = uncached[start : start + _LLM_MAX_MESSAGES]
            try:
                results.update(_classify_with_llm(chunk))
            except Exception as exc:  # noqa: BLE001 - fall back per message
                logger.warning(
                    "LLM batch classification failed (%d emails); falling back: %s",
                    len(chunk),
                    exc,
                )
    if mode in ("auto", "ml") and uncached:
        results = _fill_ml_fallback(uncached, results)

    for record in uncached:
        _cache_put(
            record["message_id"], results.get(record["message_id"], FALLBACK_CATEGORY)
        )
    return {
        record["message_id"]: _cache_get(record["message_id"])
        or results.get(record["message_id"], FALLBACK_CATEGORY)
        for record in record_list
    }


def reset() -> None:
    """Drop the cached classifier and predictions (mainly for tests)."""
    global _classifier, _load_failed
    _classifier = None
    _load_failed = None
    with _cache_lock:
        _cache.clear()
