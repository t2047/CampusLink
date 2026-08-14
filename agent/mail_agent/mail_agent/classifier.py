"""Email classification integration for the CampusLink mail service.

Wraps the trained classifier in ``agent/mail_agent/ml`` (``email_classifier.py``
plus ``models/email_classifier.joblib``) and exposes a lazy, per-message cached
``classify()`` used when Gmail messages are mapped to ``MailMessage``.

The model predicts one of four categories: ``campus``, ``career``, ``finance``
or ``other``. Classification is best-effort: if the model cannot be loaded or a
single message fails, the mail service keeps working and that message falls
back to ``other``.

The model path can be overridden with the ``MAIL_CLASSIFIER_MODEL`` env var.
"""

from __future__ import annotations

import logging
import os
import sys
import time
from pathlib import Path
from typing import Any

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

# Small in-process prediction cache: the same message id (page re-loads, list +
# detail) should not re-run the model every time.
_CACHE_MAX_ENTRIES = 2000
_CACHE_TTL_SECONDS = 3600

_classifier: Any | None = None
_load_failed: Exception | None = None
_cache: dict[str, tuple[float, str]] = {}


def _ensure_classifier() -> Any | None:
    """Load (once) and return the classifier, or ``None`` when unavailable."""
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


def classify(
    message_id: str,
    subject: str | None = "",
    body: str | None = "",
    sender: str | None = "",
    sender_email: str | None = None,
) -> str:
    """Return one of the four mail categories for ``message_id``.

    Predictions are cached per message id for a bounded window; any failure
    (missing/broken model, unexpected category) falls back to ``other``.
    """
    now = time.monotonic()
    cached = _cache.get(message_id)
    if cached is not None:
        cached_at, category = cached
        if now - cached_at <= _CACHE_TTL_SECONDS:
            return category
        _cache.pop(message_id, None)

    category = FALLBACK_CATEGORY
    classifier = _ensure_classifier()
    if classifier is not None:
        try:
            predicted = str(
                classifier.classify(
                    subject=subject, body=body, sender=sender, sender_email=sender_email
                ).get("category", "")
            ).lower()
            if predicted in CATEGORIES:
                category = predicted
            else:
                logger.warning(
                    "Classifier returned unknown category %r; using %r",
                    predicted,
                    FALLBACK_CATEGORY,
                )
        except Exception:  # noqa: BLE001
            logger.exception(
                "Classification failed for message %s; using %s",
                message_id,
                FALLBACK_CATEGORY,
            )

    if len(_cache) >= _CACHE_MAX_ENTRIES:
        _cache.pop(next(iter(_cache)), None)
    _cache[message_id] = (now, category)
    return category


def reset() -> None:
    """Drop the cached classifier and predictions (mainly for tests)."""
    global _classifier, _load_failed
    _classifier = None
    _load_failed = None
    _cache.clear()
