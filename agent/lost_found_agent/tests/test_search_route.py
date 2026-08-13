"""POST /agent/search 轻量搜索路由测试：复用 search_candidates 打分，与聊天面板结果一致。"""

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


def search_image_payload(fingerprint: str, **overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "report_type": "FOUND",
        "images": [
            {
                "object_key": "lost-found-staging/k.png",
                "visual_fingerprint": fingerprint,
                "url": "/api/lost-found/images/staging/k.png",
            }
        ],
    }
    payload.update(overrides)
    return payload


def test_image_only_search_matches_identical_image(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """纯图搜索：完全一致的指纹 → match_found、score 1.0、含"图片特征相似"理由。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [fp]}]

    body, headers = signed_request(settings, search_image_payload(fp), action="search")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 200
    result = cast(dict[str, Any], response.json())
    assert result["status"] == "match_found"
    assert result["match_results"][0]["item_id"] == "7"
    assert result["match_results"][0]["match_score"] == 1.0
    assert any("图片特征相似" in r for r in result["match_results"][0]["match_reason"])
    assert [call[0] for call in fake_api.calls] == ["search_found_items"]


def test_image_search_uses_keyword_for_ranking(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    """keyword 不参与后端硬过滤（search_candidates 只按 category/date 拉候选），
    而是进入 in-memory 打分：文字不匹配的候选被视觉之外的 text 分量拉低到阈值以下。"""
    fp = visual_fingerprint(embed_image(make_solid_png((0, 255, 0))))
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
    query_fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    unrelated_fp = visual_fingerprint(embed_image(make_solid_png((255, 0, 0))))
    fake_api.candidates = [{**candidate(7, "黑色耳机", 0), "visualFingerprints": [unrelated_fp]}]

    body, headers = signed_request(settings, search_image_payload(query_fp), action="search")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 200
    result = cast(dict[str, Any], response.json())
    assert result["status"] == "no_match"
    assert result["match_results"] == []


def test_image_search_lost_direction(
    client: TestClient, settings: Settings, fake_api: FakeCampusApiClient
) -> None:
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
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
    assert [call[0] for call in fake_api.calls] == ["search_lost_items"]
    assert result["match_results"][0]["report_type"] == "LOST"


def test_search_rejects_wrong_intended_action(client: TestClient, settings: Settings) -> None:
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    body, headers = signed_request(settings, search_image_payload(fp), action="invoke")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 403


def test_search_requires_images(client: TestClient, settings: Settings) -> None:
    body, headers = signed_request(settings, {"report_type": "FOUND"}, action="search")
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 422


def test_search_rejects_inverted_date_range(client: TestClient, settings: Settings) -> None:
    fp = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    body, headers = signed_request(
        settings,
        search_image_payload(fp, date_from="2026-08-10", date_to="2026-08-01"),
        action="search",
    )
    response = client.post("/agent/search", content=body, headers=headers)

    assert response.status_code == 422
