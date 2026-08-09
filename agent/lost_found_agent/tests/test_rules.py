from typing import Any, cast

from fastapi.testclient import TestClient

from lost_found_agent.config import Settings

from .conftest import FakeCampusApiClient
from .helpers import signed_request


def invoke(
    client: TestClient,
    settings: Settings,
    message: str,
    *,
    shared_data: dict[str, Any] | None = None,
    confirmed: bool = False,
    confirmation_id: str | None = None,
    user_id: str = "42",
    trace_id: str = "rule-request",
) -> dict[str, Any]:
    payload = {
        "message": message,
        "conversation_context": {
            "session_id": "rule-session",
            "shared_data": shared_data or {},
        },
        "confirmed": confirmed,
        "confirmation_id": confirmation_id,
        "trace_parent": {"trace_id": trace_id},
    }
    body, headers = signed_request(settings, payload, user_id=user_id)
    response = client.post("/agent/invoke", content=body, headers=headers)
    assert response.status_code == 200
    return cast(dict[str, Any], response.json())


def test_english_multiturn_report_requires_confirmation_before_writing(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    first = invoke(client, settings, "I lost my black headphones")

    assert first["status"] == "needs_more_info"
    assert first["shared_context"]["category"] == "ELECTRONICS"
    assert fake_api.calls == []

    second = invoke(
        client,
        settings,
        "description: Black wireless headphones with a scratched cloth case; "
        "location: Central Library; date: 2026-08-08",
        shared_data=first["shared_context"],
        trace_id="report-confirmation",
    )

    assert second["status"] == "needs_confirmation"
    assert second["confirmation_required"]["action"] == "report_lost"
    assert fake_api.calls == []

    fake_api.candidates = [candidate(7, "Black headphones", 0)]
    third = invoke(
        client,
        settings,
        "yes",
        confirmed=True,
        confirmation_id=second["confirmation_required"]["confirmation_id"],
        trace_id="report-execute",
    )

    assert third["status"] == "match_found"
    assert third["match_results"][0]["item_id"] == "7"
    assert [call[0] for call in fake_api.calls] == ["report_lost", "search_found_items"]


def test_natural_chinese_report_is_supported_by_rule_fallback(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    result = invoke(
        client,
        settings,
        "我在2026-08-08下午于中央图书馆丢了一副黑色耳机，耳机盒上有橙色贴纸。",
        trace_id="chinese-natural-report",
    )

    assert result["status"] == "needs_confirmation"
    assert result["shared_context"]["item_name"] == "黑色耳机"
    assert result["shared_context"]["location"] == "中央图书馆"
    assert fake_api.calls == []


def test_confirmation_is_one_time_and_bound_to_user(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    prepared = invoke(
        client,
        settings,
        "claim item ID: 7 because the left ear cup has my engraved student number",
        trace_id="claim-prepare",
    )
    confirmation_id = prepared["confirmation_required"]["confirmation_id"]

    cross_user = invoke(
        client,
        settings,
        "confirm",
        confirmed=True,
        confirmation_id=confirmation_id,
        user_id="99",
        trace_id="claim-cross-user",
    )
    assert cross_user["status"] == "failed"
    assert fake_api.calls == []

    accepted = invoke(
        client,
        settings,
        "confirm",
        confirmed=True,
        confirmation_id=confirmation_id,
        trace_id="claim-accepted",
    )
    assert accepted["status"] == "completed"
    assert [call[0] for call in fake_api.calls] == ["claim_item"]

    replay = invoke(
        client,
        settings,
        "confirm again",
        confirmed=True,
        confirmation_id=confirmation_id,
        trace_id="claim-replay",
    )
    assert replay["status"] == "failed"
    assert len(fake_api.calls) == 1


def test_claim_requires_long_enough_proof(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    result = invoke(client, settings, "claim item ID: 9 proof: mine")

    assert result["status"] == "needs_more_info"
    assert "ownership proof" in result["response"]
    assert fake_api.calls == []


def test_search_returns_explainable_top_five_in_score_order(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    fake_api.candidates = [
        candidate(index, "Black wireless headphones", day_offset=index - 1) for index in range(1, 8)
    ]
    fake_api.candidates[-1]["category"] = "BAG"
    result = invoke(
        client,
        settings,
        "search item: Black wireless headphones; category: electronics; "
        "colour: black; location: Central Library; date: 2026-08-08",
    )

    assert result["status"] == "match_found"
    assert len(result["match_results"]) == 5
    scores = [item["match_score"] for item in result["match_results"]]
    assert scores == sorted(scores, reverse=True)
    assert "Same item category" in result["match_results"][0]["match_reason"]
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]


def test_chinese_detail_response_and_sse_events(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    result = invoke(
        client,
        settings,
        "查看记录 7 的详情",
        trace_id="detail-events",
    )
    assert result["status"] == "completed"
    assert "记录 #7" in result["response"]

    stream_body, stream_headers = signed_request(settings, None, action="stream")
    stream = client.request(
        "GET",
        "/agent/stream",
        params={"request_id": "detail-events"},
        content=stream_body,
        headers=stream_headers,
    )
    assert stream.status_code == 200
    assert "event: agent_start" in stream.text
    assert "event: tool_execution" in stream.text
    assert "event: token" in stream.text
    assert "event: agent_done" in stream.text


def candidate(item_id: int, name: str, day_offset: int) -> dict[str, object]:
    day = 8 - min(day_offset, 7)
    return {
        "id": item_id,
        "itemName": name,
        "category": "ELECTRONICS",
        "description": "Black wireless headphones in a scratched cloth case",
        "colour": "Black",
        "location": "Central Library",
        "eventDate": f"2026-08-{day:02d}",
        "status": "OPEN",
    }
