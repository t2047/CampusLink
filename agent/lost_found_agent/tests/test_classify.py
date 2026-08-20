"""POST /agent/classify 端点测试：规则优先 + LLM 兜底 + fail-open。

覆盖的功能点：
- 安全：未签名请求 401、action 不匹配 403、nonce 防重放、空 item_name 422；
- 规则优先：中英文关键词、车辆归 OTHER、复合词（遥控汽车）不被覆盖、子串顺序回归（车钥匙）；
- LLM 兜底：仅在规则未命中时才调用模型，LLM 返回 category=None 也透传 null；
- fail-open：LLM 输出非法 JSON/超时/429/无效枚举时一律返回 200 + category=None，绝不 5xx；
- 分类器独立于主运行模式：即使主模式是 rules，只要配置了 key 仍可 LLM 兜底。

被测模块：``lost_found_agent.main`` 的 /agent/classify 路由 + ``lost_found_agent.llm.LlmInterpreter``。

测试策略：混合单元/集成。
- 前半部分用 ``client`` fixture（rules 模式、无 LLM key）验证纯规则行为；
- 后半部分用 ``test_llm.app_with_model``（httpx.MockTransport 拦截 HTTP 请求、
  mode=llm、mock-api-key）验证 LLM 兜底与 fail-open 分支。
"""

from typing import cast

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
) -> httpx.Response:
    """用 classify 动作签名请求 /agent/classify，返回原始 HTTP 响应。

    action 参数可覆盖，用于测试错误动作（invoke）被拒绝的场景。
    """
    body, headers = signed_request(settings, {"item_name": item_name}, action=action)
    return cast(httpx.Response, client.post("/agent/classify", content=body, headers=headers))


# ─── 规则优先（client fixture：rules 模式、无 LLM key） ──────────────


def test_classify_requires_security_headers(client: TestClient) -> None:
    """未携带签名/JWT 头直接调用 /agent/classify → 401。"""
    response = client.post("/agent/classify", json={"item_name": "耳机"})
    assert response.status_code == 401


def test_classify_zh_rules_hit(client: TestClient, settings: Settings) -> None:
    """中文规则命中：黑色耳机 → ELECTRONICS（电子类）。"""
    response = classify(client, settings, "黑色耳机")
    assert response.status_code == 200
    assert response.json() == {"category": "ELECTRONICS"}


def test_classify_en_rules_hit(client: TestClient, settings: Settings) -> None:
    """英文规则命中：wallet → WALLET_PURSE（钱包/包袋类）。"""
    response = classify(client, settings, "wallet")
    assert response.status_code == 200
    assert response.json() == {"category": "WALLET_PURSE"}


def test_classify_vehicle_rules_hit_other(client: TestClient, settings: Settings) -> None:
    """车辆无专属类别，规则优先落入 OTHER（2026-08-11 优化）。"""
    # 遍历"汽车"与"车辆"两个同义词，二者都应归 OTHER
    for name in ("汽车", "车辆"):
        response = classify(client, settings, name)
        assert response.status_code == 200, name
        assert response.json() == {"category": "OTHER"}, name


def test_classify_vehicle_compound_keeps_electronics(
    client: TestClient, settings: Settings
) -> None:
    """遥控 先于 汽车 命中：遥控汽车 归 ELECTRONICS，不被 汽车→OTHER 覆盖。

    回归保护：规则表匹配顺序不能因"车辆→OTHER"的加入而改变已有更具体词条的优先级。
    """
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
    """规则未命中且没有 LLM key → 返回 category=None（不报错，由调用方自行兜底）。"""
    response = classify(client, settings, "mystery gadget")
    assert response.status_code == 200
    assert response.json() == {"category": None}


def test_classify_action_is_scoped(client: TestClient, settings: Settings) -> None:
    """令牌 intended_action 是 invoke 而非 classify → 403 拒绝。"""
    response = classify(client, settings, "耳机", action="invoke")
    assert response.status_code == 403


def test_classify_empty_item_name_is_422(client: TestClient) -> None:
    """空 item_name → 422 参数校验失败（最小长度限制）。"""
    response = client.post("/agent/classify", json={"item_name": ""})
    assert response.status_code == 422


def test_classify_rejects_reused_nonce(client: TestClient, settings: Settings) -> None:
    """防重放：同一个 nonce 签名同一请求体，第一次放行、第二次拒绝。"""
    body, headers = signed_request(
        settings, {"item_name": "耳机"}, action="classify", nonce="fixed-nonce-123"
    )
    # 首次使用 nonce → 放行
    assert client.post("/agent/classify", content=body, headers=headers).status_code == 200
    # 重放同一 nonce → 401
    assert client.post("/agent/classify", content=body, headers=headers).status_code == 401


# ─── LLM 兜底（app_with_model：mock transport，mode=llm） ────────────


def test_classify_llm_called_only_on_rules_miss() -> None:
    """规则命中时绝不调用 LLM；只有规则未命中才调模型兜底。"""
    model_calls: list[httpx.Request] = []  # 记录 handler 收到的模型请求

    # MockTransport 的 handler：拦截所有外部 HTTP 请求并返回固定的 LLM 分类结果
    def handler(request: httpx.Request) -> httpx.Response:
        model_calls.append(request)
        return model_response({"category": "BOOKS_STATIONERY"})

    fake_api = FakeCampusApiClient()
    # app_with_model：构造 mode=llm + mock 传输的应用实例（复用 test_llm 的工厂）
    client, settings = app_with_model(handler, fake_api)
    with client:
        # 规则命中：不调 LLM
        hit = classify(client, settings, "钥匙")
        assert hit.status_code == 200
        assert hit.json() == {"category": "KEYS"}
        assert model_calls == []  # 规则命中 → 模型零调用

        # 规则未命中：调 LLM 兜底
        miss = classify(client, settings, "sticky note")
        assert miss.status_code == 200
        assert miss.json() == {"category": "BOOKS_STATIONERY"}
        assert len(model_calls) == 1  # 恰好一次模型请求
        # 校验请求目标与鉴权头：走的是 mock 基址 + Bearer mock-api-key
        assert model_calls[0].url == "https://mock-llm.test/v1/chat/completions"
        assert model_calls[0].headers["Authorization"] == "Bearer mock-api-key"


def test_classify_llm_unsure_returns_null() -> None:
    """LLM 返回 category=None（模型也不确定）→ 透传 null，不报错。"""
    # handler 固定返回 category=None
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
    # 四类异常 handler：非法 JSON、ReadTimeout、429 限流、无效枚举（TELEPORT）
    handlers = [
        lambda _: model_response("not-json"),
        lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("timeout", request=request)),
        lambda _: model_response("rate limited", status_code=429),
        lambda _: model_response({"category": "TELEPORT"}),
    ]
    # 逐个 handler 走一遍，保证每种故障都 fail-open（不向上冒 5xx）
    for index, handler in enumerate(handlers):
        fake_api = FakeCampusApiClient()
        client, settings = app_with_model(handler, fake_api)
        with client:
            response = classify(client, settings, "mystery gadget")
        # 断言信息带 index，定位到具体失败的 handler
        assert response.status_code == 200, f"handler {index}"
        assert response.json() == {"category": None}, f"handler {index}"


def test_classify_uses_llm_in_rules_mode_with_key() -> None:
    """分类器独立于主 mode：rules 模式配了 key 也能 LLM 兜底。

    主模式虽然锁定为 rules，但只要提供了 LLM key，分类器规则未命中时仍走模型。
    本用例手动组装 Settings + LlmInterpreter（而非用 client fixture），
    以精确控制"rules 模式 + 有 key"这一组合。
    """
    model_calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        model_calls.append(request)
        return model_response({"category": "CLOTHING"})

    settings = Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_agent_mode="rules",  # 主模式 rules
        lost_found_llm_api_key="mock-api-key",  # 但配置了 LLM key
        lost_found_llm_base_url="https://mock-llm.test/v1",
    )
    # 用 MockTransport 替代真实网络，handler 记录模型调用
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    interpreter = LlmInterpreter(settings, http_client)
    fake_api = FakeCampusApiClient()
    # 手动组装应用：create_app(settings, fake_api, interpreter)
    with TestClient(create_app(settings, fake_api, interpreter)) as client:
        response = classify(client, settings, "mystery gadget")
        assert response.status_code == 200
        assert response.json() == {"category": "CLOTHING"}
        assert len(model_calls) == 1  # 规则未命中 → 恰好一次 LLM 兜底调用
