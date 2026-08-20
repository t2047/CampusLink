"""规则引擎（RuleEngine）端到端测试：规则优先解析意图/字段、确认流与多轮共享上下文。

覆盖的功能点：
- 报失/捡获报告：中英文自然语言经规则提取 item_name/location/event_date/category/colour，
  写操作必须先走 needs_confirmation 确认流，确认后才真正调用后端写接口；
- 确认绑定：confirmation_id 一次性使用且绑定用户，跨用户/重放均失败；
- claim（认领）路径：所有权证明过短需追问，字段非法时顶层兜底降级；
- 图片搜索：纯图、占位语、图+文字、跨轮 shared_data 携带视觉指纹，命中理由含"图片特征相似"；
- 搜索：显式中文搜索优先于丢失背景，返回 top5 并按分数降序、含可解释理由；
- 日期与字段健壮性：LLM 幻觉未来日期/非法 claim 字段被降级为 needs_more_info，不冒泡成"内部错误"；
- 颜色提取：中英文语言保持、同义词归一、词边界避免误命中（backpack/redemption）；
- 详情 + SSE：查看记录详情并能在 /agent/stream 重放事件流。

被测模块：``lost_found_agent.rules``（RuleEngine、detect_explicit_intent、extract_colour），
配套 ``confirmation.ConfirmationStore``、``embeddings.embed_image/visual_fingerprint``。

测试策略：混合集成/单元。
- 多数用例通过 ``client``/``settings``/``fake_api`` fixture（rules 模式 + FakeCampusApiClient）
  走真实 HTTP /agent/invoke，用 helpers.signed_request 签名、helpers.make_solid_png 造测试图；
- 末尾两个 async 用例直接实例化 RuleEngine 做单元级驱动，模拟"LLM 已解释字段"的输入，
  校验顶层 handle 对非法字段/未来日期的兜底行为。
"""

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
from lost_found_agent.rules import (
    RuleEngine,
    detect_explicit_intent,
    extract_colour,
)
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
    """带签名调用一次 /agent/invoke 并断言返回 200，解包响应 JSON。

    参数映射到请求体：
    - shared_data → conversation_context.shared_data（多轮共享上下文，跨轮携带已提取字段/指纹）；
    - confirmed + confirmation_id → 用于提交上一步返回的确认（二阶段写操作）；
    - user_id → 覆盖 JWT 的 sub（测试跨用户绑定）；
    - trace_id → trace_parent.trace_id（兼作事件流 request_id）；
    - images → 暂存图信息列表（object_key / visual_fingerprint / url）。
    """
    payload = {
        "message": message,
        "conversation_context": {
            "session_id": "rule-session",
            "shared_data": shared_data or {},
        },
        "confirmed": confirmed,  # 是否携带确认标记（第二次请求带 true）
        "confirmation_id": confirmation_id,  # 服务端下发的确认单 ID
        "trace_parent": {"trace_id": trace_id},
    }
    if images:
        payload["images"] = images
    # signed_request 生成 JWT + HMAC 签名头（默认 user_id="42"）
    body, headers = signed_request(settings, payload, user_id=user_id)
    response = client.post("/agent/invoke", content=body, headers=headers)
    assert response.status_code == 200  # 调用本身必须成功，后续再断言业务结果
    return cast(dict[str, Any], response.json())


def test_english_multiturn_report_requires_confirmation_before_writing(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """英文报失走多轮确认流：先追问信息 → 确认 → 写入，期间绝不提前调后端。

    三段式验证：
    1. "I lost my black headphones" 信息不全 → needs_more_info，仅提取出 category；
    2. 补全描述/地点/日期 → needs_confirmation（要求 report_lost），仍无后端调用；
    3. 携带确认单确认后 → 才真正 report_lost + search_found_items，并命中候选 #7。
    """
    first = invoke(client, settings, "I lost my black headphones")

    assert first["status"] == "needs_more_info"  # 信息不足，先追问
    assert first["shared_context"]["category"] == "ELECTRONICS"  # 但仍先识别出电子类
    assert fake_api.calls == []  # 追问阶段不写后端

    # 第二轮：以第一轮共享上下文为底，补充结构化描述字段
    second = invoke(
        client,
        settings,
        "description: Black wireless headphones with a scratched cloth case; "
        "location: Central Library; date: 2026-08-08",
        shared_data=first["shared_context"],  # 多轮上下文续传
        trace_id="report-confirmation",
    )

    assert second["status"] == "needs_confirmation"  # 字段齐了，要求确认
    assert second["confirmation_required"]["action"] == "report_lost"
    assert fake_api.calls == []  # 确认前依然不写后端

    # 第三轮：用户确认（confirmed=True + confirmation_id），此时才执行写操作
    fake_api.candidates = [candidate(7, "Black headphones", 0)]
    third = invoke(
        client,
        settings,
        "yes",
        confirmed=True,  # 携带确认标记
        confirmation_id=second["confirmation_required"]["confirmation_id"],
        trace_id="report-execute",
    )

    assert third["status"] == "match_found"
    assert third["match_results"][0]["item_id"] == "7"
    # 确认后调用顺序：先登记报失，再搜拾取登记找匹配
    assert [call[0] for call in fake_api.calls] == ["report_lost", "search_found_items"]


def test_natural_chinese_report_is_supported_by_rule_fallback(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """中文自然语言报失：规则引擎无需 LLM 即可抽取关键字段。

    一句话里带日期/地点/物品，规则能定位"丢"的语义 → 进入确认而非追问，
    且抽出的 item_name / location 与中文原文一致。
    """
    result = invoke(
        client,
        settings,
        "我在2026-08-08下午于中央图书馆丢了一副黑色耳机，耳机盒上有橙色贴纸。",
        trace_id="chinese-natural-report",
    )

    assert result["status"] == "needs_confirmation"
    assert result["shared_context"]["item_name"] == "黑色耳机"  # 中文原样保留
    assert result["shared_context"]["location"] == "中央图书馆"
    assert fake_api.calls == []  # 确认前不写后端


def test_natural_chinese_found_report_requires_confirmation_before_writing(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """中文捡获登记同样走确认流：确认后才 report_found + 反向搜报失匹配。"""
    prepared = invoke(
        client,
        settings,
        "我在2026-08-08于中央图书馆捡到一副黑色耳机，耳机盒上有橙色贴纸。",
        trace_id="found-report-confirmation",
    )

    assert prepared["status"] == "needs_confirmation"
    assert prepared["confirmation_required"]["action"] == "report_found"  # 捡到 → 登记捡获
    assert prepared["shared_context"]["category"] == "ELECTRONICS"
    assert fake_api.calls == []  # 确认前不写后端

    # 模拟后端存在一条匹配的报失记录（候选放 lost_candidates）
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
    assert completed["actions_taken"][1]["action"] == "search_lost_items"  # 反向搜报失
    assert completed["match_results"][0]["report_type"] == "LOST"  # 命中的是报失记录
    assert completed["match_results"][0]["item_name"] == "报失的黑色耳机"
    assert "描述：" in completed["response"]  # 回复里回显了描述字段
    assert [call[0] for call in fake_api.calls] == ["report_found", "search_lost_items"]


def test_report_with_staged_image_flows_images_to_confirmed_create(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """带暂存图的报失：图信息进入 shared_context，确认后原样传给后端写接口。"""
    prepared = invoke(
        client,
        settings,
        "我在2026-08-08于中央图书馆丢了一副黑色耳机，耳机盒上有橙色贴纸。",
        images=[
            {
                "object_key": "lost-found-staging/k.png",  # 暂存区对象键
                "visual_fingerprint": "VF1:fp",  # 视觉指纹（这里用假指纹即可）
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
        trace_id="report-with-image",
    )

    assert prepared["status"] == "needs_confirmation"
    # 图信息被抽取进共享上下文：object_key 与指纹分列两个字段
    assert prepared["shared_context"]["images"] == ["lost-found-staging/k.png"]
    assert prepared["shared_context"]["visual_fingerprints"] == ["VF1:fp"]
    assert fake_api.calls == []  # 确认前不写后端

    completed = invoke(
        client,
        settings,
        "确认",
        confirmed=True,
        confirmation_id=prepared["confirmation_required"]["confirmation_id"],
        shared_data=prepared["shared_context"],  # 把图信息随上下文带回
        trace_id="report-with-image-execute",
    )

    assert completed["status"] == "completed"
    assert [call[0] for call in fake_api.calls] == ["report_lost", "search_found_items"]
    # 取出 fake_api 记录的 report_lost 入参，断言图字段被透传
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

    # 纯图搜索直接调用 search_found_items，指纹作为查询条件
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]
    assert result["shared_context"]["visual_fingerprints"] == ["VF1:fp"]


def test_placeholder_image_search_matches_identical_image(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """仅发图占位语"帮我找这个"不应抽取"这个"作为 keyword：否则近零的 text 分量
    会把完全一致的图片匹配（visual=1.0）拉低到最低阈值以下。回归：占位语只走视觉。"""
    # 计算真实指纹：查询与候选都来自同一张纯蓝色 PNG → 视觉必为 1.0
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]}]
    result = invoke(
        client,
        settings,
        "帮我找这个",  # 占位语：不应被当作可搜索的文本条件
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
    assert result["match_results"][0]["match_score"] == 1.0  # 纯视觉满分，未被 text 拉低
    assert any("图片特征相似" in r for r in result["match_results"][0]["match_reason"])
    assert "keyword" not in result["shared_context"]  # 占位语没有被抽成 keyword


def test_image_search_adds_visual_reason_and_persists_across_turns(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """图+文字搜索：命中理由含"图片特征相似"，且指纹在下一轮仍随 shared_data 携带。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]}]
    result = invoke(
        client,
        settings,
        "帮我找黑色耳机",  # 既有占位意图又有实物关键词
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

    # 第二轮不带新图，仅复用上一轮 shared_context → 图片与指纹仍应保留
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
    """捡获登记即使搜不到匹配的报失记录，也应正常完成（登记本身成功）。"""
    prepared = invoke(
        client,
        settings,
        "我在2026-08-08于中央图书馆捡到一副黑色耳机，耳机盒上有橙色贴纸。",
        trace_id="found-report-no-match-confirmation",
    )

    # 确认后执行：fake_api.lost_candidates 为空 → 反向搜索无命中
    completed = invoke(
        client,
        settings,
        "确认登记",
        confirmed=True,
        confirmation_id=prepared["confirmation_required"]["confirmation_id"],
        trace_id="found-report-no-match-execute",
    )

    assert completed["status"] == "completed"  # 登记完成，不是失败
    assert completed["match_results"] == []  # 无匹配项
    assert "暂时未找到高匹配的报失记录" in completed["response"]  # 友善提示无匹配
    assert [call[0] for call in fake_api.calls] == ["report_found", "search_lost_items"]


def test_chinese_found_with_ambiguous_find_word_is_not_treated_as_search() -> None:
    """"找到"一词有歧义（可作"找到/捡到"或"搜索"），纯单元验证意图判定。

    当句中同时出现"找到"+"为我创建"（明确的创建诉求）时，应判为 report_found 而非搜索。
    """
    # 直接调用规则引擎的意图检测函数，验证歧义词消解
    intent = detect_explicit_intent("我前天在UHC找到一把红色的伞，为我创建")

    assert intent == "report_found"


def test_chinese_found_with_ambiguous_find_word_enters_creation_confirmation(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """歧义"找到"走整条 HTTP 链路：被判为登记捡获并进入确认流。"""
    prepared = invoke(
        client,
        settings,
        "我前天在UHC找到一把红色的伞，为我创建",
        trace_id="found-with-ambiguous-find-word",
    )

    assert prepared["status"] == "needs_confirmation"
    assert prepared["confirmation_required"]["action"] == "report_found"
    # 规则同时抽取了类别、颜色、地点
    assert prepared["shared_context"]["category"] == "UMBRELLA"
    assert prepared["shared_context"]["colour"] == "红色"
    assert prepared["shared_context"]["location"] == "UHC"
    assert fake_api.calls == []  # 确认前不写后端


def test_natural_english_found_report_extracts_required_fields(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """英文捡获登记：自然语言可直接抽取必填字段（名称/地点/日期）。"""
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
    assert prepared["shared_context"]["event_date"] == "2026-08-08"  # 日期解析成 ISO 格式
    assert fake_api.calls == []


def test_confirmation_is_one_time_and_bound_to_user(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """确认单是"一次性 + 用户绑定"的：跨用户与重放都必须失败，且不执行任何工具。"""
    # 第一步：claim 物品需要先过确认（所有权证明足够长 → 进入确认）
    prepared = invoke(
        client,
        settings,
        "claim item ID: 7 because the left ear cup has my engraved student number",
        trace_id="claim-prepare",
    )
    confirmation_id = prepared["confirmation_required"]["confirmation_id"]

    # 场景 A：换一个用户（user_id=99）使用同一确认单 → 失败
    cross_user = invoke(
        client,
        settings,
        "confirm",
        confirmed=True,
        confirmation_id=confirmation_id,
        user_id="99",  # 换成别的用户
        trace_id="claim-cross-user",
    )
    assert cross_user["status"] == "failed"
    assert fake_api.calls == []  # 跨用户确认不得执行任何工具

    # 场景 B：真正的持有者确认 → 成功，执行 claim_item
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

    # 场景 C：确认单已被消费，再次重放 → 失败且不再产生新的工具调用
    replay = invoke(
        client,
        settings,
        "confirm again",
        confirmed=True,
        confirmation_id=confirmation_id,
        trace_id="claim-replay",
    )
    assert replay["status"] == "failed"
    assert len(fake_api.calls) == 1  # 仍只有场景 B 那一次 claim_item


def test_claim_requires_long_enough_proof(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """claim 的所有权证明太短 → 追问（needs_more_info），拒绝直接认领。"""
    result = invoke(client, settings, "claim item ID: 9 proof: mine")  # proof 只有单词

    assert result["status"] == "needs_more_info"
    assert "ownership proof" in result["response"]  # 提示需补充所有权证明
    assert fake_api.calls == []  # 不执行认领


def test_search_returns_explainable_top_five_in_score_order(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """搜索返回最多 5 条结果、按分数降序，且每条都带可解释的命中理由。"""
    # 构造 7 条候选：id 1..7，日期越新（day_offset 越小）越靠后，最后一条强制改成 BAG 类别
    fake_api.candidates = [
        candidate(index, "Black wireless headphones", day_offset=index - 1) for index in range(1, 8)
    ]
    fake_api.candidates[-1]["category"] = "BAG"  # #7 类别不一致，用于区分排序理由
    result = invoke(
        client,
        settings,
        "search item: Black wireless headphones; category: electronics; "
        "colour: black; location: Central Library; date: 2026-08-08",
    )

    assert result["status"] == "match_found"
    assert len(result["match_results"]) == 5  # 顶多返回前 5 名
    scores = [item["match_score"] for item in result["match_results"]]
    assert scores == sorted(scores, reverse=True)  # 严格按分数降序
    # 排第一的理由包含"Same item category"（同类命中）这一可解释因素
    assert "Same item category" in result["match_results"][0]["match_reason"]
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]


def test_explicit_chinese_search_wins_over_lost_item_background(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """显式"帮我找…丢失的…"搜索意图优先于报失背景：直接搜索而非登记。

    中文里"丢了"通常暗示报失，但"帮我找…丢失的伞"是明确搜索——规则应识别
    搜索优先，不进入确认流、不执行写操作，只搜拾取登记并命中候选 #6。
    """
    # 手动构造一条精确匹配的候选（UMBRELLA / 蓝色 / 工程学院一号楼大厅 / 2026-08-09）
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
    assert result["confirmation_required"] is None  # 搜索无需确认
    assert result["match_results"][0]["item_id"] == "6"
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]  # 只调了搜索


def test_chinese_explicit_search_with_find_word_remains_search(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """含"找到"字眼的显式搜索仍需保持搜索语义，不得被歧义误判为登记。"""
    result = invoke(
        client,
        settings,
        "帮我找到一把在UHC丢失的红色雨伞",
        trace_id="explicit-search-with-find-word",
    )

    assert result["confirmation_required"] is None  # 是搜索不是写操作
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]


def test_chinese_detail_response_and_sse_events(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """查看记录详情：返回完成状态与"记录 #7"文案，且详情工具调用在事件流中可重放。"""
    result = invoke(
        client,
        settings,
        "查看记录 7 的详情",
        trace_id="detail-events",
    )
    assert result["status"] == "completed"
    assert "记录 #7" in result["response"]  # 详情文案回显记录编号

    # 用 stream 动作签名请求事件流，按 request_id 重放刚才的调用
    stream_body, stream_headers = signed_request(settings, None, action="stream")
    stream = client.request(
        "GET",
        "/agent/stream",
        params={"request_id": "detail-events"},
        content=stream_body,
        headers=stream_headers,
    )
    assert stream.status_code == 200
    # SSE 事件流完整覆盖：开始 → 工具执行 → 令牌 → 结束
    assert "event: agent_start" in stream.text
    assert "event: tool_execution" in stream.text  # 详情工具调用被记录
    assert "event: token" in stream.text
    assert "event: agent_done" in stream.text


def candidate(
    item_id: int,
    name: str,
    day_offset: int,
    *,
    report_type: str = "FOUND",
) -> dict[str, object]:
    """构造一条后端搜索候选（模拟 Campus API 返回的 JSON 结构）。

    day_offset 控制 eventDate 的新旧：8 - min(day_offset, 7) 保证日期都在
    2026-08 合法范围内，偏移越大日期越早；默认 report_type=FOUND。
    测试常通过 {**candidate(...), "visualFingerprints": [fp]} 追加视觉指纹。
    """
    day = 8 - min(day_offset, 7)
    return {
        "id": item_id,
        "reportType": report_type,  # FOUND=拾取登记，LOST=失主报失
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
    # @pytest.mark.asyncio 场景：直接异步驱动 RuleEngine.handle，不走 HTTP 层。
    # 这里模拟"LLM 已解释出 report_found + 各字段"的输入，验证顶层兜底逻辑。
    engine = RuleEngine(fake_api, ConfirmationStore(ttl_seconds=600), 0.5)  # 阈值 0.5
    payload = InvokeRequest(
        message="I found a student card on the second floor of the library",
        conversation_context=ConversationContext(session_id="lf-session", shared_data={}),
        trace_parent=TraceParent(trace_id="rule-future-date"),
    )
    # verified 携带当前请求的已认证身份
    verified = VerifiedRequest(
        user_id="42", user_role="STUDENT", intended_action="invoke", nonce=""
    )
    future = (date.today() + timedelta(days=5)).isoformat()  # 未来 5 天的日期

    # engine.handle：传入 interpreted_intent/interpreted_fields 模拟 LLM 解释结果
    response = await engine.handle(
        payload,
        verified,
        "rule-future-date",
        lambda _event: None,  # 事件回调：这里直接丢弃
        interpreted_intent="report_found",
        interpreted_fields={
            "item_name": "Student card",
            "category": "ID_CARD",
            "description": "A student card found on the second floor of the library",
            "location": "Central Library",
            "event_date": future,  # ← 非法：未来日期应被清掉并追问
        },
    )

    assert response.status == "needs_more_info"  # 降级为追问
    assert "event_date" not in response.shared_context  # 非法字段已被清除
    assert future not in response.response  # 未来日期不进入回复文案
    assert fake_api.calls == []  # 未执行任何后端调用


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

    # 模拟 LLM 把 claim 的 report_id 幻觉成一个无法解析为 int 的字符串
    response = await engine.handle(
        payload,
        verified,
        "rule-claim-invalid",
        lambda _event: None,  # 事件回调：直接丢弃
        interpreted_intent="claim_item",
        interpreted_fields={
            "report_id": "not-an-int",  # LLM 幻觉值：无法解析为 int
            "proof_description": "It is my card with my name on the back",
        },
    )

    assert response.status == "needs_more_info"  # 字段非法 → 追问而非报错
    assert "内部错误" not in response.response  # 不得出现"内部错误"文案
    assert fake_api.calls == []  # 不执行认领


def test_extract_colour_preserves_input_language() -> None:
    """extract_colour：颜色抽取保持输入语言，并对同义词做归一（如 ivory→White）。"""
    # 中文输入抽中文展示形式
    assert extract_colour("我丢了白色水杯") == "白色"
    assert extract_colour("米白水杯") == "白色"  # 米白归一为白色
    # 英文输入抽英文展示形式（含同义词归一）
    assert extract_colour("colour: black") == "Black"
    assert extract_colour("a white backpack") == "White"
    assert extract_colour("ivory phone case") == "White"  # ivory→White
    assert extract_colour("navy blue jacket") == "Blue"  # navy→Blue


def test_extract_colour_word_boundary_avoids_false_positives() -> None:
    """extract_colour：词边界匹配，避免把颜色子串误命进无关单词。"""
    assert extract_colour("black backpack") == "Black"  # black 先命中
    assert extract_colour("a grey backpack") == "Grey"
    # "backpack" 不含颜色、"redemption" 不命中 red
    assert extract_colour("backpack") is None  # 不以颜色开头/包含 → None
    assert extract_colour("redemption arc") is None  # 不能把 red 命中到 redemption 内部
    assert extract_colour("") is None  # 空串 → None
