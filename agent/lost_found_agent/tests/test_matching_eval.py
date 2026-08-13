import subprocess
import sys
from pathlib import Path

from lost_found_agent.matching_eval import compare, evaluate, load_cases

FIXTURE = Path(__file__).parent / "fixtures" / "matching_regression.jsonl"


def test_corpus_loads_all_cases() -> None:
    cases = load_cases(FIXTURE)

    assert len(cases) == 10
    assert all(case.language in {"zh", "en"} for case in cases)
    assert all(isinstance(case.query, dict) for case in cases)


def test_multimodal_beats_rule_on_visual_discrimination() -> None:
    cases = load_cases(FIXTURE)

    rule = evaluate(cases, "rule")
    embedding = evaluate(cases, "embedding")
    multimodal = evaluate(cases, "multimodal")

    assert rule["scored_cases"] == 9
    assert multimodal["mrr"] == 1.0
    assert rule["mrr"] < multimodal["mrr"]
    assert embedding["mrr"] == rule["mrr"]
    assert multimodal["ndcg_at_5"] >= rule["ndcg_at_5"]
    assert multimodal["recall_at_5"] == 1.0


def test_compare_picks_multimodal_as_mrr_winner() -> None:
    cases = load_cases(FIXTURE)
    results = [evaluate(cases, variant) for variant in ("rule", "embedding", "multimodal")]

    comparison = compare(results)

    assert comparison["mrr"]["best"] == "multimodal"
    assert comparison["mrr"]["delta_from_rule"]["multimodal"] > 0
    assert "recall_at_5" in comparison
    assert "ndcg_at_5" in comparison


def test_compare_requires_rule_baseline() -> None:
    cases = load_cases(FIXTURE)

    try:
        compare([evaluate(cases, "embedding")])
    except ValueError as exc:
        assert "rule baseline" in str(exc)
    else:
        raise AssertionError("expected ValueError without rule baseline")


def test_cli_supports_single_variant() -> None:
    completed = subprocess.run(  # noqa: S603 - 固定解释器 + 固定参数，非不可信输入
        [
            sys.executable,
            "-m",
            "lost_found_agent.matching_eval",
            str(FIXTURE),
            "--variant",
            "embedding",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert '"variant": "embedding"' in completed.stdout
    assert '"comparison"' not in completed.stdout


def test_cli_defaults_to_all_with_comparison() -> None:
    completed = subprocess.run(  # noqa: S603 - 固定解释器 + 固定参数，非不可信输入
        [
            sys.executable,
            "-m",
            "lost_found_agent.matching_eval",
            str(FIXTURE),
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert '"variant": "rule"' in completed.stdout
    assert '"variant": "multimodal"' in completed.stdout
    assert '"comparison"' in completed.stdout
    assert '"best"' in completed.stdout


def test_cli_single_rule_variant_no_comparison() -> None:
    completed = subprocess.run(  # noqa: S603 - 固定解释器 + 固定参数，非不可信输入
        [
            sys.executable,
            "-m",
            "lost_found_agent.matching_eval",
            str(FIXTURE),
            "--variant",
            "rule",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert '"variant": "rule"' in completed.stdout
    assert '"embedding"' not in completed.stdout
    assert '"comparison"' not in completed.stdout


def test_evaluate_rejects_unknown_variant() -> None:
    cases = load_cases(FIXTURE)

    try:
        evaluate(cases, "unknown")
    except ValueError as exc:
        assert "unknown variant" in str(exc)
    else:
        raise AssertionError("expected ValueError for unknown variant")
