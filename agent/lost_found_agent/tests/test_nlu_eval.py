from pathlib import Path

from lost_found_agent.nlu_eval import evaluate_cases, load_cases


def test_nlu_regression_fixture_generates_quality_report() -> None:
    cases = load_cases(Path(__file__).parent / "fixtures" / "nlu_regression.jsonl")

    report = evaluate_cases(cases)

    assert report["total"] == 4
    assert report["intent_accuracy"] == 1.0
    assert report["field_completeness"] >= 0.9
    assert report["mistaken_write_rate"] == 0.0
    assert report["failures"] == []
