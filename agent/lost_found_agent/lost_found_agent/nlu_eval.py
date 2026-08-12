"""Reusable NLU regression evaluation for Lost & Found intent parsing."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .rules import ALLOWED_CONTEXT_FIELDS, detect_explicit_intent, extract_fields


@dataclass(frozen=True)
class EvaluationCase:
    message: str
    intent: str
    fields: dict[str, Any]
    must_not_write: bool = False


def load_cases(path: Path) -> list[EvaluationCase]:
    cases: list[EvaluationCase] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        payload = json.loads(stripped)
        fields = payload.get("fields", {})
        if not isinstance(fields, dict):
            raise ValueError(f"line {line_number}: fields must be an object")
        cases.append(
            EvaluationCase(
                message=str(payload["message"]),
                intent=str(payload["intent"]),
                fields={
                    key: value for key, value in fields.items() if key in ALLOWED_CONTEXT_FIELDS
                },
                must_not_write=bool(payload.get("must_not_write", False)),
            )
        )
    return cases


def evaluate_cases(cases: list[EvaluationCase]) -> dict[str, Any]:
    total = len(cases)
    intent_hits = 0
    expected_fields = 0
    field_hits = 0
    mistaken_writes = 0
    failures: list[dict[str, Any]] = []

    for case in cases:
        predicted_intent = detect_explicit_intent(case.message) or "search_found_items"
        predicted_fields = extract_fields(case.message, predicted_intent)
        intent_ok = predicted_intent == case.intent
        intent_hits += int(intent_ok)

        missing_fields: list[str] = []
        for field, expected in case.fields.items():
            expected_fields += 1
            if predicted_fields.get(field) == expected:
                field_hits += 1
            else:
                missing_fields.append(field)

        is_write = predicted_intent in {"report_lost", "claim_item"}
        if case.must_not_write and is_write:
            mistaken_writes += 1

        if not intent_ok or missing_fields or (case.must_not_write and is_write):
            failures.append(
                {
                    "message": case.message,
                    "expected_intent": case.intent,
                    "predicted_intent": predicted_intent,
                    "missing_or_wrong_fields": missing_fields,
                    "must_not_write": case.must_not_write,
                }
            )

    return {
        "total": total,
        "intent_accuracy": round(intent_hits / total, 4) if total else 0.0,
        "field_completeness": round(field_hits / expected_fields, 4) if expected_fields else 1.0,
        "mistaken_write_rate": round(mistaken_writes / total, 4) if total else 0.0,
        "failures": failures,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate Lost & Found NLU regression corpus")
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = evaluate_cases(load_cases(args.corpus))
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)


if __name__ == "__main__":
    main()
