"""POST /agent/search 轻量搜索路由测试：复用 search_candidates 打分，与聊天面板结果一致。

覆盖的功能点：
- 纯图搜索：视觉指纹完全一致 → match_found、score 1.0、含"图片特征相似"理由；
- keyword 只参与内存打分不参与后端硬过滤，文字不匹配的候选会被剔除；
- 无关颜色指纹 → no_match（不会误命中不同颜色）；
- report_type=LOST 时走 search_lost_items 反向搜索（寻物启事）；
- 鉴权/参数校验：错误 action → 403，缺 images → 422，倒置日期区间 → 422。

被测模块：``lost_found_agent.main`` 的 /agent/search 路由，内部复用规则引擎的
``search_candidates`` 打分逻辑——因此本组结果与聊天面板中的搜索行为保持一致。

测试策略：集成测试。用 ``client``/``settings``/``fake_api`` fixture（rules 模式 + 假后端）
通过 TestClient 打真实端点；图片用 ``make_solid_png`` 生成纯色 PNG，再经
``embed_image`` + ``visual_fingerprint`` 计算真实指纹；请求经 ``signed_request`` 签名。
"""

from typing import Any, cast

from fastapi.testclient import TestClient

from lost_found_agent.config import Settings
from lost_found_agent.embeddings import embed_image, visual_fingerprint

from .conftest import FakeCampusApiClient
from .helpers import make_solid_png, signed_request


def candidate(
    item_id: int,
    name: str,
    day_offset: int,
    *,
    report_type: str = "FOUND",
) -> dict[str, object]:
    """构造一条后端搜索候选记录（模拟 Campus API 返回的 JSON 结构）。

    day_offset 控制 eventDate：8 - min(day_offset, 7)，保证日期都在 2026-08 的
    合法范围内，且偏移越大日期越早，用于构造不同新旧程度的候选。
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


def search_image_payload(fingerprint: str, **overrides: Any) -> dict[str, Any]:
    """构造 /agent/search 的请求体：默认只有一张 staging 图 + FOUND 方向。

    overrides 允许测试覆盖任意字段（如 keyword、report_type、date_from/date_to）。
    """
    payload: dict[str, Any] = {
        "report_type": "FOUND",  # 默认搜拾取登记（用户丢东西想找回）
        "images": [
            {
                "object_key": "lost-found-staging/k.png",
                "visual_fingerprint": fingerprint,  # 由测试传入的真实视觉指纹
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
    }
    payload.update(overrides)  # 测试按需覆盖/追加字段
    return payload


def test_image_only_search_matches_identical_image(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """纯图搜索：完全一致的指纹 → match_found、score 1.0、含"图片特征相似"理由。"""
    # 用同一张纯蓝色 PNG 同时作为查询图和候选图的指纹来源 → 指纹必然相同
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    # 给候选 #7 打上与查询一致的 visualFingerprints
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]}]

    # 用 action="search" 签名，请求体为纯图（无 keyword）
    body, headers = signed_request(settings, search_image_payload(fp), action="search")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 200
    result = cast(dict[str, Any], response.json())
    assert result["status"] == "match_found"
    assert result["match_results"][0]["item_id"] == "7"
    assert result["match_results"][0]["match_score"] == 1.0  # 指纹完全相同 → 视觉满分
    # 命中理由里必须出现"图片特征相似"说明
    assert any("图片特征相似" in r for r in result["match_results"][0]["match_reason"])
    # 只调用了 search_found_items（搜拾取登记）
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]


def test_image_search_uses_keyword_for_ranking(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """keyword 不参与后端硬过滤（search_candidates 只按 category/date 拉候选），
    而是进入 in-memory 打分：文字不匹配的候选被视觉之外的 text 分量拉低到阈值以下。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 255, 0))))
    # 两条候选的视觉指纹都与查询相同（视觉均 1.0），只有文字匹配度不同：
    #   #8 是"红色雨伞"（与 keyword"耳机"无关）；#7 是"黑色耳机"（命中）
    fake_api.candidates = [
        {**candidate(8, "红色雨伞", 0), "visualFingerprints": [fp]},
        {**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]},
    ]

    body, headers = signed_request(
        settings, search_image_payload(fp, keyword="耳机"), action="search"
    )
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 200
    result = cast(dict[str, Any], response.json())
    # 文字不匹配的候选 #8（视觉同样 1.0）被 text 分量拖到 0.35 以下剔除；#7 保留并排第一
    assert [item["item_id"] for item in result["match_results"]] == ["7"]


def test_image_search_no_match_for_unrelated_image(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """不同色指纹 → 视觉分量判别失败 → no_match（不会误命中不同颜色的候选）。"""
    query_fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))  # 查询：纯蓝
    unrelated_fp = visual_fingerprint(embed_image(make_solid_png((255, 0, 0))))  # 候选：纯红
    # 候选的指纹与查询不同 → 视觉相似度低
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [unrelated_fp]}]

    body, headers = signed_request(settings, search_image_payload(query_fp), action="search")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 200
    result = cast(dict[str, Any], response.json())
    assert result["status"] == "no_match"  # 无任何候选超过阈值
    assert result["match_results"] == []


def test_image_search_lost_direction(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """report_type=LOST（用户捡到东西想找失主）→ 走 search_lost_items 反向搜索。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    # 候选放在 lost_candidates（对应 search_lost_items 的返回）
    fake_api.lost_candidates = [
        {**candidate(7, "黑色耳机", 0, report_type="LOST"), "visualFingerprints": [fp]}
    ]

    body, headers = signed_request(
        settings, search_image_payload(fp, report_type="LOST"), action="search"
    )
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 200
    result = cast(dict[str, Any], response.json())
    assert result["status"] == "match_found"
    # 反向搜索必须调用 search_lost_items，而不是 search_found_items
    assert [call[0] for call in fake_api.calls] == ["search_lost_items"]
    assert result["match_results"][0]["report_type"] == "LOST"


def test_search_rejects_wrong_intended_action(client: TestClient, settings: Settings) -> None:
    """令牌 intended_action 与 /agent/search 不匹配（用 invoke 签名）→ 403。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    body, headers = signed_request(settings, search_image_payload(fp), action="invoke")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 403


def test_search_requires_images(client: TestClient, settings: Settings) -> None:
    """请求体缺少 images（纯 /agent/search 必须基于图）→ 422 参数校验失败。"""
    body, headers = signed_request(settings, {"report_type": "FOUND"}, action="search")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 422


def test_search_rejects_inverted_date_range(client: TestClient, settings: Settings) -> None:
    """date_from 晚于 date_to（区间倒置）→ 422 参数校验失败。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    body, headers = signed_request(
        settings,
        search_image_payload(fp, date_from="2026-08-10", date_to="2026-08-01"),  # 起止倒置
        action="search",
    )
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 422
