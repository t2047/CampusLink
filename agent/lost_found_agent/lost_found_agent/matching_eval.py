"""可复现的匹配排序评估：在固定语料上对比规则/文本嵌入/多模态三个版本。

指标计算使用 minimum_score=0.0，避免默认 0.35 阈值把相关项过滤掉而污染
Recall@K 等排序指标。`--variant all` 输出逐指标对比表和每个指标的胜者。
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from math import log2
from pathlib import Path
from typing import Any

from .matching import rank_candidates

VARIANTS = ("rule", "embedding", "multimodal")
_METRICS = ("recall_at_5", "precision_at_5", "mrr", "ndcg_at_5")


@dataclass(frozen=True)
class RankingCase:
    query: dict[str, Any]
    candidates: list[dict[str, Any]]
    relevant: frozenset[str]
    language: str


def load_cases(path: Path) -> list[RankingCase]:
    cases: list[RankingCase] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        payload = json.loads(stripped)
        query = payload.get("query")
        candidates = payload.get("candidates")
        if not isinstance(query, dict) or not isinstance(candidates, list):
            raise ValueError(f"line {line_number}: query/candidates must be an object and a list")
        relevant = frozenset(str(item_id) for item_id in payload.get("relevant", []))
        cases.append(
            RankingCase(
                query=query,
                candidates=candidates,
                relevant=relevant,
                language=str(payload.get("language", "zh")),
            )
        )
    return cases


def evaluate(
    cases: list[RankingCase],
    variant: str,
    minimum_score: float = 0.0,
) -> dict[str, Any]:
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant {variant!r}; expected one of {VARIANTS}")
    recall: list[float] = []
    precision: list[float] = []
    reciprocal: list[float] = []
    ndcg: list[float] = []
    first_ranks: list[int] = []
    scored = 0

    for case in cases:
        if not case.relevant:
            continue
        prepared, embedding_enabled = _prepare(case, variant)
        results = rank_candidates(
            prepared.query,
            prepared.candidates,
            minimum_score,
            prepared.language,
            text_embedding=embedding_enabled,
        )
        ranked_ids = [result.item_id for result in results]
        hits = [index for index, item_id in enumerate(ranked_ids) if item_id in prepared.relevant]
        scored += 1
        recall.append(len(hits) / len(prepared.relevant))
        precision.append(len(hits) / 5)
        reciprocal.append(1.0 / (hits[0] + 1) if hits else 0.0)
        ndcg.append(_ndcg_at_k(ranked_ids, prepared.relevant, 5))
        if hits:
            first_ranks.append(hits[0] + 1)

    return {
        "variant": variant,
        "scored_cases": scored,
        "recall_at_5": _mean(recall),
        "precision_at_5": _mean(precision),
        "mrr": _mean(reciprocal),
        "ndcg_at_5": _mean(ndcg),
        "mean_first_relevant_rank": _mean(first_ranks),
    }


def compare(results: list[dict[str, Any]]) -> dict[str, Any]:
    if not any(result["variant"] == "rule" for result in results):
        raise ValueError("rule baseline is required for comparison")
    by_metric: dict[str, Any] = {}
    for metric in _METRICS:
        scores = {result["variant"]: result[metric] for result in results}
        best = max(scores, key=lambda variant: scores[variant])
        baseline = scores["rule"]
        by_metric[metric] = {
            "values": scores,
            "best": best,
            "delta_from_rule": {
                variant: round(scores[variant] - baseline, 4) for variant in scores
            },
        }
    return by_metric


def _prepare(case: RankingCase, variant: str) -> tuple[RankingCase, bool]:
    if variant == "rule":
        return strip_visual(case), False
    if variant == "embedding":
        return strip_visual(case), True
    return case, True


def strip_visual(case: RankingCase) -> RankingCase:
    def without_visual(value: dict[str, Any]) -> dict[str, Any]:
        return {
            key: item
            for key, item in value.items()
            if key not in {"visual_fingerprint", "visualFingerprints"}
        }

    return RankingCase(
        query=without_visual(case.query),
        candidates=[without_visual(candidate) for candidate in case.candidates],
        relevant=case.relevant,
        language=case.language,
    )


def _ndcg_at_k(ranked_ids: list[str], relevant: frozenset[str], k: int) -> float:
    dcg = sum(
        1.0 / log2(index + 2) for index, item_id in enumerate(ranked_ids[:k]) if item_id in relevant
    )
    ideal_count = min(len(relevant), k)
    idcg = sum(1.0 / log2(index + 2) for index in range(ideal_count)) if ideal_count else 0.0
    return dcg / idcg if idcg else 0.0


def _mean(values: list[float] | list[int]) -> float:
    if not values:
        return 0.0
    return round(sum(values) / len(values), 4)


def _format_report(results: list[dict[str, Any]], comparison: dict[str, Any]) -> str:
    header = f"{'metric':<22}" + "".join(f"{variant:>14}" for variant in VARIANTS) + f"{'best':>12}"
    lines = [header]
    for metric in _METRICS:
        values = comparison[metric]["values"]
        best = comparison[metric]["best"]
        line = f"{metric:<22}" + "".join(f"{values[variant]:>14.4f}" for variant in VARIANTS)
        line += f"{best:>12}"
        lines.append(line)
    lines.append("")
    for metric in _METRICS:
        deltas = comparison[metric]["delta_from_rule"]
        lines.append(
            f"{metric}: delta vs rule -> "
            + ", ".join(f"{variant}={delta:+.4f}" for variant, delta in deltas.items())
        )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Evaluate matching ranking variants on a JSONL corpus"
    )
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--variant", choices=[*VARIANTS, "all"], default="all")
    parser.add_argument("--min-score", type=float, default=0.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    cases = load_cases(args.corpus)
    variants = list(VARIANTS) if args.variant == "all" else [args.variant]
    results = [evaluate(cases, variant, args.min_score) for variant in variants]
    report = {
        "corpus": str(args.corpus),
        "cases": len(cases),
        "min_score": args.min_score,
        "results": results,
    }
    comparison = None
    if args.variant == "all":
        comparison = compare(results)
        report["comparison"] = comparison
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    if comparison is not None:
        print(_format_report(results, comparison))
        print("\nDetailed JSON written to stdout below (pipe to a file or use --output):")
    print(text)


if __name__ == "__main__":
    main()
