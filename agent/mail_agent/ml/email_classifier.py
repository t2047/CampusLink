#!/usr/bin/env python3
"""Reusable email classifier module.

Wraps the trained model (models/email_classifier.joblib) produced by
train_email_model.py.  Given an email's subject, body and sender, it returns
the predicted category plus per-class scores.

Python API:

    from email_classifier import EmailClassifier

    clf = EmailClassifier()                     # loads models/email_classifier.joblib
    result = clf.classify(
        subject="ISS Admissions - Payment",
        body="Your tuition payment is due.",
        sender="iss-admissions@nus.edu.sg",
    )
    # result == {
    #     "category": "campus",
    #     "confidence": 0.87,
    #     "scores": {"campus": 0.87, "career": 0.03, "finance": 0.06, "other": 0.04},
    # }

The sender may be a bare address ("a@b.com"), the RFC-5322 form
("Name <a@b.com>"), or just a display name; an embedded email address is
preferred when present.

Command line:

    python email_classifier.py --subject "..." --body "..." --sender "a@b.com"
    python email_classifier.py --input new_emails.json --output predictions.json

Notes:
  * ``scores`` are softmax-normalized decision-function strengths from the
    LinearSVC.  They sum to 1 but are *not* calibrated probabilities, because
    LinearSVC has no ``predict_proba``.
  * Text preprocessing (HTML unescape, tag/URL/address removal, sender-domain
    extraction) mirrors train_email_model.py exactly, so predictions are
    consistent with training.
"""

from __future__ import annotations

import argparse
import html as html_mod
import json
import re
from pathlib import Path
from typing import Any, Iterable

import joblib
import numpy as np
from scipy import sparse


DEFAULT_MODEL_PATH = "models/email_classifier.joblib"

HTML_TAG_RE = re.compile(r"<[^>]+>")
URL_RE = re.compile(r"https?://\S+", re.IGNORECASE)
EMAIL_RE = re.compile(r"[\w.+-]+@[\w.-]+")


def clean_text(raw: str | None) -> str:
    """Normalize subject/body text exactly like training: unescape HTML, drop tags/URLs/addresses."""
    if not raw:
        return ""
    text = html_mod.unescape(raw)
    text = HTML_TAG_RE.sub(" ", text)
    text = URL_RE.sub(" ", text)
    text = EMAIL_RE.sub(" ", text)
    text = text.replace("\r", " ")
    return " ".join(text.split())


def extract_domain(sender_email: str) -> str:
    """Return the lower-cased domain of an email address ('' if unknown)."""
    if not sender_email:
        return ""
    email = str(sender_email).strip().lower()
    if "@" in email:
        email = email.rsplit("@", 1)[1]
    return email.strip()


def extract_sender_domain(sender: str | None) -> str:
    """Best-effort domain extraction from any sender string.

    Handles bare addresses, "Name <a@b.com>", and display names.  Falls back
    to the raw string when no address is present (same as training behavior).
    """
    if not sender:
        return ""
    raw = str(sender)
    match = EMAIL_RE.search(raw)
    if match:
        return extract_domain(match.group(0))
    return extract_domain(raw)


def _softmax(scores: np.ndarray) -> np.ndarray:
    scores = np.asarray(scores, dtype=float)
    scores = scores - scores.max()
    exp = np.exp(scores)
    total = exp.sum()
    if total == 0 or not np.isfinite(total):
        return np.full(scores.shape, 1.0 / scores.size, dtype=float)
    return exp / total


class EmailClassifier:
    """Loads the trained email model and predicts categories for new emails."""

    def __init__(self, model_path: str | Path = DEFAULT_MODEL_PATH):
        self.model_path = Path(model_path)
        self.artifact: dict[str, Any] | None = None

    def _ensure_loaded(self) -> None:
        if self.artifact is not None:
            return
        if not self.model_path.exists():
            raise FileNotFoundError(
                f"Model file not found: {self.model_path}. "
                "Train it first with: python train_email_model.py"
            )
        artifact = joblib.load(self.model_path)
        for key in ("text_vectorizer", "sender_vectorizer", "classifier"):
            if key not in artifact:
                raise ValueError(f"Model artifact is missing '{key}': {self.model_path}")
        self.artifact = artifact

    @property
    def categories(self) -> list[str]:
        self._ensure_loaded()
        classes = self.artifact.get("classes") or self.artifact["classifier"].classes_
        return [str(c) for c in classes]

    def classify(
        self,
        subject: str | None = "",
        body: str | None = "",
        sender: str | None = "",
        sender_email: str | None = None,
    ) -> dict[str, Any]:
        """Classify one email.

        Args:
            subject: email subject line.
            body: email body text (plain or HTML; HTML is stripped).
            sender: sender name/address, e.g. "ISS Admissions" or "a@b.com".
            sender_email: explicit sender address; takes precedence over
                ``sender`` when the address cannot be found in it.

        Returns:
            {"category": ..., "confidence": ..., "scores": {category: score}}
        """
        self._ensure_loaded()
        sender_domain = extract_sender_domain(sender_email) or extract_sender_domain(sender)
        text = clean_text(f"{subject or ''}\n{body or ''}") or "(empty)"

        text_matrix = self.artifact["text_vectorizer"].transform([text])
        sender_matrix = self.artifact["sender_vectorizer"].transform([sender_domain])
        X = sparse.hstack([text_matrix, sender_matrix]).tocsr()

        clf = self.artifact["classifier"]
        classes = [str(c) for c in getattr(clf, "classes_", self.categories)]
        if hasattr(clf, "predict_proba"):
            proba = clf.predict_proba(X)[0]
            scores = {str(c): float(p) for c, p in zip(classes, proba)}
            confidence = float(max(proba))
            category = str(clf.predict(X)[0])
        else:
            raw = np.asarray(clf.decision_function(X))[0]
            proba = _softmax(raw)
            scores = {str(c): float(p) for c, p in zip(classes, proba)}
            confidence = float(max(proba))
            category = str(classes[int(np.argmax(raw))])

        return {
            "category": category,
            "confidence": round(confidence, 4),
            "scores": {c: round(p, 4) for c, p in scores.items()},
        }

    def classify_many(self, records: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
        """Classify a list of email dicts with keys subject/body/sender/sender_email."""
        results = []
        for rec in records:
            results.append(
                self.classify(
                    subject=rec.get("subject"),
                    body=rec.get("body"),
                    sender=rec.get("sender"),
                    sender_email=rec.get("sender_email"),
                )
            )
        return results

    def __repr__(self) -> str:
        if self.artifact is None:
            return f"EmailClassifier(model={str(self.model_path)!r}, not loaded)"
        return f"EmailClassifier(model={str(self.model_path)!r}, classes={self.categories})"


def classify_email(
    subject: str | None = "",
    body: str | None = "",
    sender: str | None = "",
    sender_email: str | None = None,
    model_path: str | Path = DEFAULT_MODEL_PATH,
) -> dict[str, Any]:
    """Convenience one-shot wrapper: load the model, classify one email, return the result."""
    return EmailClassifier(model_path).classify(
        subject=subject, body=body, sender=sender, sender_email=sender_email
    )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--subject", help="email subject")
    p.add_argument("--body", help="email body (HTML is stripped automatically)")
    p.add_argument("--sender", help="sender name or email address")
    p.add_argument("--sender-email", help="explicit sender email address")
    p.add_argument("--input", help="JSON file with one email dict or a list of them")
    p.add_argument("--output", help="write predictions JSON to this file (default: stdout)")
    p.add_argument("--model", default=DEFAULT_MODEL_PATH, help=f"model file (default: {DEFAULT_MODEL_PATH})")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    clf = EmailClassifier(args.model)

    if args.input:
        with open(args.input, "r", encoding="utf-8-sig") as fh:
            payload = json.load(fh)
        records = payload if isinstance(payload, list) else [payload]
        results = clf.classify_many(records)
        out = results if isinstance(payload, list) else results[0]
    else:
        out = clf.classify(
            subject=args.subject,
            body=args.body,
            sender=args.sender,
            sender_email=args.sender_email,
        )

    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
        print(f"Wrote predictions -> {args.output}")
    else:
        print(text)


if __name__ == "__main__":
    main()
