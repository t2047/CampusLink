import json
from pathlib import Path

import httpx
import pytest

from lost_found_agent.config import Settings, get_settings
from lost_found_agent.model_eval import main, run_evaluation, skipped_report
from lost_found_agent.nlu_eval import EvaluationCase, load_cases

FIXTURE = Path(__file__).parent / "fixtures" / "model_regression.jsonl"


def make_settings() -> Settings:
    return Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_llm_api_key="mock-key",
        lost_found_llm_input_cost_per_1m=1000.0,
        lost_found_llm_output_cost_per_1m=2000.0,
    )


def valid_handler(request: httpx.Request) -> httpx.Response:
    body = json.loads(request.content)
    user = json.loads(body["messages"][1]["content"])
    message = user["message"]
    content = (
        {"intent": "search_found_items", "fields": {"keyword": "test"}, "language": "en"}
        if "search" in message
        else {"intent": "report_lost", "fields": {"item_name": "red key pouch"}, "language": "zh"}
    )
    return httpx.Response(
        200,
        json={
            "choices": [{"message": {"content": json.dumps(content)}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5},
        },
    )


async def test_run_evaluation_reports_quality_latency_and_cost() -> None:
    cases = [
        EvaluationCase(
            "please search for the test keyword",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        ),
        EvaluationCase(
            "please search for the test keyword again",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        ),
    ]
    client = httpx.AsyncClient(transport=httpx.MockTransport(valid_handler))

    report = await run_evaluation(cases, make_settings(), client=client)
    await client.aclose()

    assert report["status"] == "completed"
    assert report["attempts"] == 3
    assert report["intent_accuracy"] == 1.0
    assert report["field_completeness"] == 1.0
    assert report["mistaken_write_rate"] == 0.0
    assert report["failures"] == []
    assert report["tokens"] == {"input": 20, "output": 10}
    assert report["estimated_cost_usd"] == 0.04
    assert report["cost_configured"] is True
    assert report["latency_ms"]["p95"] >= 0.0
    assert report["latency_ms"]["p50"] <= report["latency_ms"]["p99"]


async def test_run_evaluation_uses_production_retry_path() -> None:
    calls = 0

    def flaky_handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": "not valid json"}}]},
            )
        return valid_handler(request)

    cases = [
        EvaluationCase(
            "please search for the test keyword",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        )
    ]
    client = httpx.AsyncClient(transport=httpx.MockTransport(flaky_handler))

    report = await run_evaluation(cases, make_settings(), client=client, attempts=2)
    await client.aclose()

    assert calls == 2
    assert report["intent_accuracy"] == 1.0
    assert report["failures"] == []


async def test_run_evaluation_counts_unparseable_model_output_as_failure() -> None:
    def failing_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": "not valid json"}}]})

    cases = [
        EvaluationCase(
            "search the test keyword",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        )
    ]
    client = httpx.AsyncClient(transport=httpx.MockTransport(failing_handler))

    report = await run_evaluation(cases, make_settings(), client=client)
    await client.aclose()

    assert report["intent_accuracy"] == 0.0
    assert len(report["failures"]) == 1
    assert "error" in report["failures"][0]


def test_model_regression_corpus_loads() -> None:
    cases = load_cases(FIXTURE)

    assert len(cases) == 8
    assert all(
        case.intent in {"report_lost", "search_found_items", "get_item_detail", "claim_item"}
        for case in cases
    )


def test_skipped_report_shape() -> None:
    report = skipped_report("corpus.jsonl", 8)

    assert report["status"] == "skipped"
    assert report["cases"] == 8


def test_main_skips_without_api_key(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("sys.argv", ["model_eval", str(FIXTURE)])
    monkeypatch.setenv("LOST_FOUND_LLM_API_KEY", "")
    get_settings.cache_clear()

    main()

    output = capsys.readouterr().out
    assert "skipped" in output
