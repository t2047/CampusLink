"""POST /agent/classify 端点测试：规则优先 + LLM 兜底 + fail-open。"""

import httpx
from fastapi.testclient import TestClient

from lost_found_agent.config import Settings
from lost_found_agent.llm import LlmInterpreter
from lost_found_agent.main import create_app

from .conftest import FakeCampusApiClient
from .helpers import signed_request
from .test_llm import app_with_model, model_response


def classify(
    client: TestClient,
    settings: Settings,
    item_name: str,
    *,
    action: str = "classify",
):
    body, headers = signed_request(settings, {"item_name": item_name}, action=action)
    return client.post("/agent/classify", content=body, headers=headers)


# ─── 规则优先（client fixture：rules 模式、无 LLM key） ──────────────


def test_classify_requires_security_headers(client: TestClient) -> None:
    response = client.post("/agent/classify", json={"item_name": "耳机"})
    assert response.status_code == 401


def test_classify_zh_rules_hit(client: TestClient, settings: Settings) -> None:
    response = classify(client, settings, "黑色耳机")
    assert response.status_code == 200
    assert response.json() == {"category": "ELECTRONICS"}


def test_classify_en_rules_hit(client: TestClient, settings: Settings) -> None:
    response = classify(client, settings, "wallet")
    assert response.status_code == 200
    assert response.json() == {"category": "WALLET_PURSE"}


def test_classify_vehicle_rules_hit_other(client: TestClient, settings: Settings) -> None:
    """车辆无专属类别，规则优先落入 OTHER（2026-08-11 优化）。"""
    for name in ("汽车", "车辆"):
        response = classify(client, settings, name)
        assert response.status_code == 200, name
        assert response.json() == {"category": "OTHER"}, name


def test_classify_vehicle_compound_keeps_electronics(
    client: TestClient, settings: Settings
) -> None:
    """遥控 先于 汽车 命中：遥控汽车 归 ELECTRONICS，不被 汽车→OTHER 覆盖。"""
    response = classify(client, settings, "遥控汽车")
    assert response.status_code == 200
    assert response.json() == {"category": "ELECTRONICS"}


def test_classify_car_keys_still_keys(client: TestClient, settings: Settings) -> None:
    """子串顺序回归保护：车钥匙 必须仍是 KEYS，不受 汽车→OTHER 影响。"""
    response = classify(client, settings, "车钥匙")
    assert response.status_code == 200
    assert response.json() == {"category": "KEYS"}


def test_classify_rules_miss_returns_null_without_key(
    client: TestClient, settings: Settings
) -> None:
    response = classify(client, settings, "mystery gadget")
    assert response.status_code == 200
    assert response.json() == {"category": None}


def test_classify_action_is_scoped(client: TestClient, settings: Settings) -> None:
    response = classify(client, settings, "耳机", action="invoke")
    assert response.status_code == 403


def test_classify_empty_item_name_is_422(client: TestClient) -> None:
    response = client.post("/agent/classify", json={"item_name": ""})
    assert response.status_code == 422


def test_classify_rejects_reused_nonce(client: TestClient, settings: Settings) -> None:
    body, headers = signed_request(
        settings, {"item_name": "耳机"}, action="classify", nonce="fixed-nonce-123"
    )
    assert client.post("/agent/classify", content=body, headers=headers).status_code == 200
    assert client.post("/agent/classify", content=body, headers=headers).status_code == 401


# ─── LLM 兜底（app_with_model：mock transport，mode=llm） ────────────


def test_classify_llm_called_only_on_rules_miss() -> None:
    model_calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        model_calls.append(request)
        return model_response({"category": "BOOKS_STATIONERY"})

    fake_api = FakeCampusApiClient()
    client, settings = app_with_model(handler, fake_api)
    with client:
        # 规则命中：不调 LLM
        hit = classify(client, settings, "钥匙")
        assert hit.status_code == 200
        assert hit.json() == {"category": "KEYS"}
        assert model_calls == []

        # 规则未命中：调 LLM 兜底
        miss = classify(client, settings, "sticky note")
        assert miss.status_code == 200
        assert miss.json() == {"category": "BOOKS_STATIONERY"}
        assert len(model_calls) == 1
        assert model_calls[0].url == "https://mock-llm.test/v1/chat/completions"
        assert model_calls[0].headers["Authorization"] == "Bearer mock-api-key"


def test_classify_llm_unsure_returns_null() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return model_response({"category": None})

    fake_api = FakeCampusApiClient()
    client, settings = app_with_model(handler, fake_api)
    with client:
        response = classify(client, settings, "mystery gadget")
    assert response.status_code == 200
    assert response.json() == {"category": None}


def test_classify_llm_errors_fail_open() -> None:
    """LLM 输出不可信/超时/429/无效枚举 → 一律 200 + category=None，绝不 5xx。"""
    handlers = [
        lambda _: model_response("not-json"),
        lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("timeout", request=request)),
        lambda _: model_response("rate limited", status_code=429),
        lambda _: model_response({"category": "TELEPORT"}),
    ]
    for index, handler in enumerate(handlers):
        fake_api = FakeCampusApiClient()
        client, settings = app_with_model(handler, fake_api)
        with client:
            response = classify(client, settings, "mystery gadget")
        assert response.status_code == 200, f"handler {index}"
        assert response.json() == {"category": None}, f"handler {index}"


def test_classify_uses_llm_in_rules_mode_with_key() -> None:
    """分类器独立于主 mode：rules 模式配了 key 也能 LLM 兜底。"""
    model_calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        model_calls.append(request)
        return model_response({"category": "CLOTHING"})

    settings = Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_agent_mode="rules",
        lost_found_llm_api_key="mock-api-key",
        lost_found_llm_base_url="https://mock-llm.test/v1",
    )
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    interpreter = LlmInterpreter(settings, http_client)
    fake_api = FakeCampusApiClient()
    with TestClient(create_app(settings, fake_api, interpreter)) as client:
        response = classify(client, settings, "mystery gadget")
        assert response.status_code == 200
        assert response.json() == {"category": "CLOTHING"}
        assert len(model_calls) == 1
