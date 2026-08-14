from datetime import date, timedelta
from typing import Any, cast

from fastapi.testclient import TestClient

from lost_found_agent.config import Settings
from lost_found_agent.confirmation import ConfirmationStore
from lost_found_agent.embeddings import embed_image, visual_fingerprint
from lost_found_agent.models import (
    ConversationContext,
    InvokeRequest,
    TraceParent,
    VerifiedRequest,
)
from lost_found_agent.rules import RuleEngine, detect_explicit_intent
from lost_found_agent.tools import ReportLostInput

from .conftest import FakeCampusApiClient
from .helpers import make_solid_png, signed_request


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
    images: list[dict[str, Any]] | None = None,
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
    if images:
        payload["images"] = images
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


def test_natural_chinese_found_report_requires_confirmation_before_writing(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    prepared = invoke(
        client,
        settings,
        "我在2026-08-08于中央图书馆捡到一副黑色耳机，耳机盒上有橙色贴纸。",
        trace_id="found-report-confirmation",
    )

    assert prepared["status"] == "needs_confirmation"
    assert prepared["confirmation_required"]["action"] == "report_found"
    assert prepared["shared_context"]["category"] == "ELECTRONICS"
    assert fake_api.calls == []

    fake_api.lost_candidates = [candidate(8, "报失的黑色耳机", 0, report_type="LOST")]
    completed = invoke(
        client,
        settings,
        "确认登记",
        confirmed=True,
        confirmation_id=prepared["confirmation_required"]["confirmation_id"],
        trace_id="found-report-execute",
    )

    assert completed["status"] == "match_found"
    assert completed["actions_taken"][0]["action"] == "report_found"
    assert completed["actions_taken"][1]["action"] == "search_lost_items"
    assert completed["match_results"][0]["report_type"] == "LOST"
    assert completed["match_results"][0]["item_name"] == "报失的黑色耳机"
    assert "描述：" in completed["response"]
    assert [call[0] for call in fake_api.calls] == ["report_found", "search_lost_items"]


def test_report_with_staged_image_flows_images_to_confirmed_create(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    prepared = invoke(
        client,
        settings,
        "我在2026-08-08于中央图书馆丢了一副黑色耳机，耳机盒上有橙色贴纸。",
        images=[
            {
                "object_key": "lost-found-staging/k.png",
                "visual_fingerprint": "VF1:fp",
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
        trace_id="report-with-image",
    )

    assert prepared["status"] == "needs_confirmation"
    assert prepared["shared_context"]["images"] == ["lost-found-staging/k.png"]
    assert prepared["shared_context"]["visual_fingerprints"] == ["VF1:fp"]
    assert fake_api.calls == []

    completed = invoke(
        client,
        settings,
        "确认",
        confirmed=True,
        confirmation_id=prepared["confirmation_required"]["confirmation_id"],
        shared_data=prepared["shared_context"],
        trace_id="report-with-image-execute",
    )

    assert completed["status"] == "completed"
    assert [call[0] for call in fake_api.calls] == ["report_lost", "search_found_items"]
    lost_payload = cast(ReportLostInput, fake_api.calls[0][2])
    assert lost_payload.images == ["lost-found-staging/k.png"]
    assert lost_payload.visual_fingerprints == ["VF1:fp"]


def test_pure_image_search_runs_without_text_criteria(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """仅发图（"帮我找这个"，无可提取的文本条件）仍走 search_found_items，
    视觉指纹进入查询端。"""
    result = invoke(
        client,
        settings,
        "帮我找这个",
        images=[
            {
                "object_key": "lost-found-staging/k.png",
                "visual_fingerprint": "VF1:fp",
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
        trace_id="image-only-search",
    )

    assert [call[0] for call in fake_api.calls] == ["search_found_items"]
    assert result["shared_context"]["visual_fingerprints"] == ["VF1:fp"]


def test_placeholder_image_search_matches_identical_image(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """仅发图占位语"帮我找这个"不应抽取"这个"作为 keyword：否则近零的 text 分量
    会把完全一致的图片匹配（visual=1.0）拉低到最低阈值以下。回归：占位语只走视觉。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]}]
    result = invoke(
        client,
        settings,
        "帮我找这个",
        images=[
            {
                "object_key": "lost-found-staging/k.png",
                "visual_fingerprint": fp,
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
        trace_id="placeholder-image-search",
    )

    assert result["status"] == "match_found"
    assert result["match_results"][0]["item_id"] == "7"
    assert result["match_results"][0]["match_score"] == 1.0
    assert any("图片特征相似" in r for r in result["match_results"][0]["match_reason"])
    assert "keyword" not in result["shared_context"]


def test_image_search_adds_visual_reason_and_persists_across_turns(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]}]
    result = invoke(
        client,
        settings,
        "帮我找黑色耳机",
        images=[
            {
                "object_key": "lost-found-staging/k.png",
                "visual_fingerprint": fp,
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
        trace_id="image-search",
    )

    assert result["status"] == "match_found"
    assert any("图片特征相似" in reason for reason in result["match_results"][0]["match_reason"])
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]
    # 指纹在下一轮仍随 shared_data 携带（多轮共享）
    assert result["shared_context"]["visual_fingerprints"] == [fp]

    next_turn = invoke(
        client,
        settings,
        "再找一次",
        shared_data=result["shared_context"],
        trace_id="image-search-2",
    )
    assert next_turn["shared_context"]["images"] == ["lost-found-staging/k.png"]
    assert next_turn["shared_context"]["visual_fingerprints"] == [fp]


def test_found_report_without_lost_match_still_completes(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    prepared = invoke(
        client,
        settings,
        "我在2026-08-08于中央图书馆捡到一副黑色耳机，耳机盒上有橙色贴纸。",
        trace_id="found-report-no-match-confirmation",
    )

    completed = invoke(
        client,
        settings,
        "确认登记",
        confirmed=True,
        confirmation_id=prepared["confirmation_required"]["confirmation_id"],
        trace_id="found-report-no-match-execute",
    )

    assert completed["status"] == "completed"
    assert completed["match_results"] == []
    assert "暂时未找到高匹配的报失记录" in completed["response"]
    assert [call[0] for call in fake_api.calls] == ["report_found", "search_lost_items"]


def test_chinese_found_with_ambiguous_find_word_is_not_treated_as_search() -> None:
    intent = detect_explicit_intent("我前天在UHC找到一把红色的伞，为我创建")

    assert intent == "report_found"


def test_chinese_found_with_ambiguous_find_word_enters_creation_confirmation(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    prepared = invoke(
        client,
        settings,
        "我前天在UHC找到一把红色的伞，为我创建",
        trace_id="found-with-ambiguous-find-word",
    )

    assert prepared["status"] == "needs_confirmation"
    assert prepared["confirmation_required"]["action"] == "report_found"
    assert prepared["shared_context"]["category"] == "UMBRELLA"
    assert prepared["shared_context"]["colour"] == "红色"
    assert prepared["shared_context"]["location"] == "UHC"
    assert fake_api.calls == []


def test_natural_english_found_report_extracts_required_fields(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    prepared = invoke(
        client,
        settings,
        "I picked up black headphones at Central Library on 2026-08-08; "
        "the case has an orange sticker.",
        trace_id="english-found-report",
    )

    assert prepared["status"] == "needs_confirmation"
    assert prepared["confirmation_required"]["action"] == "report_found"
    assert prepared["shared_context"]["item_name"] == "black headphones"
    assert prepared["shared_context"]["location"] == "Central Library"
    assert prepared["shared_context"]["event_date"] == "2026-08-08"
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


def test_explicit_chinese_search_wins_over_lost_item_background(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    fake_api.candidates = [
        {
            "id": 6,
            "itemName": "测试用蓝色雨伞",
            "category": "UMBRELLA",
            "description": "一把蓝色长柄测试雨伞，伞柄上贴有测试标签",
            "colour": "蓝色",
            "location": "工程学院一号楼大厅",
            "eventDate": "2026-08-09",
            "status": "OPEN",
        }
    ]

    result = invoke(
        client,
        settings,
        "帮我找一把2026-08-09在工程学院一号楼大厅丢失的蓝色雨伞",
        trace_id="chinese-search-priority",
    )

    assert result["status"] == "match_found"
    assert result["confirmation_required"] is None
    assert result["match_results"][0]["item_id"] == "6"
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]


def test_chinese_explicit_search_with_find_word_remains_search(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    result = invoke(
        client,
        settings,
        "帮我找到一把在UHC丢失的红色雨伞",
        trace_id="explicit-search-with-find-word",
    )

    assert result["confirmation_required"] is None
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


def candidate(
    item_id: int,
    name: str,
    day_offset: int,
    *,
    report_type: str = "FOUND",
) -> dict[str, object]:
    day = 8 - min(day_offset, 7)
    return {
        "id": item_id,
        "reportType": report_type,
        "itemName": name,
        "category": "ELECTRONICS",
        "description": "Black wireless headphones in a scratched cloth case",
        "colour": "Black",
        "location": "Central Library",
        "eventDate": f"2026-08-{day:02d}",
        "status": "OPEN",
        "imageUrls": [f"https://images.example.test/{item_id}.jpg"],
    }


async def test_interpreted_future_date_is_dropped_and_asked(
    settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """LLM 提取字段给出未来日期时，规则引擎清除该字段并追问日期，
    而不是冒泡成"内部错误"（2026-08-11 修复）。"""
    engine = RuleEngine(fake_api, ConfirmationStore(ttl_seconds=600), 0.5)
    payload = InvokeRequest(
        message="I found a student card on the second floor of the library",
        conversation_context=ConversationContext(session_id="lf-session", shared_data={}),
        trace_parent=TraceParent(trace_id="rule-future-date"),
    )
    verified = VerifiedRequest(
        user_id="42", user_role="STUDENT", intended_action="invoke", nonce=""
    )
    future = (date.today() + timedelta(days=5)).isoformat()

    response = await engine.handle(
        payload,
        verified,
        "rule-future-date",
        lambda _event: None,
        interpreted_intent="report_found",
        interpreted_fields={
            "item_name": "Student card",
            "category": "ID_CARD",
            "description": "A student card found on the second floor of the library",
            "location": "Central Library",
            "event_date": future,
        },
    )

    assert response.status == "needs_more_info"
    assert "event_date" not in response.shared_context
    assert future not in response.response
    assert fake_api.calls == []


async def test_claim_invalid_fields_degrades_to_needs_more_info(
    settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """claim 路径字段校验失败（非 report 路径）同样由 handle 顶层兜底降级为
    needs_more_info，不冒泡成"内部错误"（2026-08-11 修复的配套覆盖）。"""
    engine = RuleEngine(fake_api, ConfirmationStore(ttl_seconds=600), 0.5)
    payload = InvokeRequest(
        message="I found a student card on the second floor of the library",
        conversation_context=ConversationContext(session_id="lf-session", shared_data={}),
        trace_parent=TraceParent(trace_id="rule-claim-invalid"),
    )
    verified = VerifiedRequest(
        user_id="42", user_role="STUDENT", intended_action="invoke", nonce=""
    )

    response = await engine.handle(
        payload,
        verified,
        "rule-claim-invalid",
        lambda _event: None,
        interpreted_intent="claim_item",
        interpreted_fields={
            "report_id": "not-an-int",  # LLM 幻觉值：无法解析为 int
            "proof_description": "It is my card with my name on the back",
        },
    )

    assert response.status == "needs_more_info"
    assert "内部错误" not in response.response
    assert fake_api.calls == []
