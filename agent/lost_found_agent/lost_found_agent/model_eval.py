"""真实模型批量评估：质量、P95 延迟与费用估算。

没有配置 `LOST_FOUND_LLM_API_KEY` 时输出 skipped 报告并退出码 0（不影响 CI）。
有 Key 时逐条顺序调用（不并发，避免扭曲延迟分位），复用 NLU 语料的语义
（意图准确率 / 字段完整率 / 误写率）并叠加延迟与 token 费用。
"""

from __future__ import annotations

import argparse
import asyncio
import json
from math import ceil
from pathlib import Path
from time import perf_counter
from typing import Any

import httpx

from .config import Settings, get_settings
from .llm import LlmInterpreter, LlmTelemetry, LlmUnavailable, interpret_with_retry
from .nlu_eval import load_cases

WRITE_INTENTS = {"report_lost", "claim_item"}


class _Collector:
    def __init__(self) -> None:
        self.records: list[LlmTelemetry] = []

    def record(self, telemetry: LlmTelemetry) -> None:
        self.records.append(telemetry)


async def run_evaluation(
    cases: Any,
    settings: Settings,
    *,
    client: httpx.AsyncClient | None = None,
    attempts: int = 3,
) -> dict[str, Any]:
    collector = _Collector()
    interpreter = LlmInterpreter(settings, client, on_complete=collector.record)
    latencies: list[float] = []
    failures: list[dict[str, Any]] = []
    intent_hits = 0
    field_hits = 0
    expected_fields = 0
    mistaken_writes = 0

    try:
        for case in cases:
            started = perf_counter()
            try:
                interpretation = await interpret_with_retry(
                    interpreter,
                    case.message,
                    {},
                    attempts=attempts,
                )
            except LlmUnavailable as exc:
                latencies.append((perf_counter() - started) * 1000.0)
                failures.append({"message": case.message, "error": str(exc)})
                continue
            latencies.append((perf_counter() - started) * 1000.0)

            predicted_intent = interpretation.intent
            predicted_fields = interpretation.fields.model_dump(exclude_none=True)
            intent_ok = predicted_intent == case.intent
            intent_hits += int(intent_ok)
            missing: list[str] = []
            for field, expected in case.fields.items():
                expected_fields += 1
                if predicted_fields.get(field) == expected:
                    field_hits += 1
                else:
                    missing.append(field)
            if case.must_not_write and predicted_intent in WRITE_INTENTS:
                mistaken_writes += 1
            if (
                not intent_ok
                or missing
                or (case.must_not_write and predicted_intent in WRITE_INTENTS)
            ):
                failures.append(
                    {
                        "message": case.message,
                        "expected_intent": case.intent,
                        "predicted_intent": predicted_intent,
                        "missing_or_wrong_fields": missing,
                        "must_not_write": case.must_not_write,
                    }
                )
    finally:
        await interpreter.close()

    input_tokens = sum(record.input_tokens for record in collector.records)
    output_tokens = sum(record.output_tokens for record in collector.records)
    total_cases = len(cases)
    return {
        "status": "completed",
        "model": settings.lost_found_llm_model,
        "attempts": attempts,
        "cases": total_cases,
        "intent_accuracy": _rate(intent_hits, total_cases),
        "field_completeness": _rate(field_hits, expected_fields),
        "mistaken_write_rate": _rate(mistaken_writes, total_cases),
        "failures": failures,
        "latency_ms": {
            "mean": _round_mean(latencies),
            "p50": _percentile(sorted(latencies), 50),
            "p95": _percentile(sorted(latencies), 95),
            "p99": _percentile(sorted(latencies), 99),
        },
        "tokens": {"input": input_tokens, "output": output_tokens},
        "estimated_cost_usd": _estimate_cost(settings, input_tokens, output_tokens),
        "cost_configured": (
            settings.lost_found_llm_input_cost_per_1m > 0
            or settings.lost_found_llm_output_cost_per_1m > 0
        ),
    }


def skipped_report(corpus: str, total_cases: int) -> dict[str, Any]:
    return {
        "status": "skipped",
        "reason": "LOST_FOUND_LLM_API_KEY 未配置；未执行真实模型评估（exit 0）",
        "corpus": corpus,
        "cases": total_cases,
    }


def _rate(hits: int, total: int) -> float:
    return round(hits / total, 4) if total else 0.0


def _round_mean(values: list[float]) -> float:
    return round(sum(values) / len(values), 2) if values else 0.0


def _percentile(sorted_values: list[float], percentile: int) -> float:
    if not sorted_values:
        return 0.0
    index = max(0, ceil(percentile / 100 * len(sorted_values)) - 1)
    return round(sorted_values[index], 2)


def _estimate_cost(settings: Settings, input_tokens: int, output_tokens: int) -> float:
    cost = (
        input_tokens / 1_000_000 * settings.lost_found_llm_input_cost_per_1m
        + output_tokens / 1_000_000 * settings.lost_found_llm_output_cost_per_1m
    )
    return round(cost, 6)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run real-model NLU batch evaluation (quality, latency, cost)"
    )
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--attempts",
        type=int,
        default=3,
        help="LLM retry attempts per case; default matches production invoke path",
    )
    args = parser.parse_args()

    settings = get_settings()
    cases = load_cases(args.corpus)
    if not settings.lost_found_llm_api_key.strip():
        report = skipped_report(str(args.corpus), len(cases))
        _emit(report, args.output)
        return

    report = asyncio.run(run_evaluation(cases, settings, attempts=max(1, args.attempts)))
    _emit(report, args.output)


def _emit(report: dict[str, Any], output: Path | None) -> None:
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if output:
        output.write_text(text + "\n", encoding="utf-8")
    if report["status"] == "skipped":
        print(report["reason"])
    else:
        print(
            f"model={report['model']} cases={report['cases']} "
            f"intent_acc={report['intent_accuracy']:.4f} "
            f"field={report['field_completeness']:.4f} "
            f"write={report['mistaken_write_rate']:.4f}"
        )
        latency = report["latency_ms"]
        print(
            f"latency(ms) mean={latency['mean']:.2f} p50={latency['p50']:.2f} "
            f"p95={latency['p95']:.2f} p99={latency['p99']:.2f} "
            f"tokens in={report['tokens']['input']} out={report['tokens']['output']} "
            f"cost=${report['estimated_cost_usd']:.6f} (configured={report['cost_configured']})"
        )
    print(text)


if __name__ == "__main__":
    main()
