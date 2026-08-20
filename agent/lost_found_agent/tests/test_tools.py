"""CampusApiClient 与后端 REST 契约之间的低层单元测试。

覆盖功能点：
- 6 个工具动作各自对应的 HTTP 方法/路由，以及携带的作用域 JWT（sub/role/intended_action）；
- report_lost 携带图片时 imageKeys 的序列化；
- 搜索过滤条件到后端查询参数（category/dateFrom/dateTo/size）的映射；
- 后端业务错误（409）与网络故障（连接失败）到 BackendApiError 的映射，
  且错误文本绝不泄露 Bearer token。

测试策略：
- 全部为异步单元测试（async def，pytest-asyncio），不依赖真实网络；
- 用 httpx.MockTransport 捕获发出的 httpx.Request，直接在本地断言；
- 通过 conftest 的 settings fixture 拿到确定性的 agent_backend_shared_secret
  来解密并校验 JWT 内容。
"""

import json
from datetime import date
from typing import Any

import httpx
import jwt
import pytest

from lost_found_agent.config import Settings
from lost_found_agent.tools import (
    BackendApiError,
    CampusApiClient,
    ClaimItemInput,
    GetItemDetailInput,
    ReportFoundInput,
    ReportLostInput,
    SearchFoundItemsInput,
    SearchLostItemsInput,
)


# 参数化：一次覆盖 6 个工具动作的（HTTP 方法, 后端路由, 意图/动作名）三元组
@pytest.mark.parametrize(
    ("method", "path", "action"),
    [
        ("POST", "/api/internal/lost-found/reports/lost", "report_lost"),
        ("POST", "/api/internal/lost-found/reports/found", "report_found"),
        ("GET", "/api/internal/lost-found/candidates", "search_found_items"),
        ("GET", "/api/internal/lost-found/lost-candidates", "search_lost_items"),
        ("GET", "/api/internal/lost-found/reports/7", "get_item_detail"),
        ("POST", "/api/internal/lost-found/reports/7/claims", "claim_item"),
    ],
)
# pytest.mark.asyncio：声明这是一个需要事件循环的异步测试（由 pytest-asyncio 提供）
async def test_each_tool_uses_expected_route_and_scoped_token(
    settings: Settings, method: str, path: str, action: str
) -> None:
    # captured 记录 MockTransport 实际发出的请求，收尾统一断言
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"ok": True})

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        # 依据动作分发到对应的方法，构造各自的输入模型并调用
        if action == "report_lost":
            await client.report_lost(
                "42",
                "STUDENT",
                ReportLostInput(
                    item_name="Black headphones",
                    category="ELECTRONICS",
                    description="Black wireless headphones in a fabric case",
                    location="Central Library",
                    event_date=date(2026, 8, 8),
                ),
            )
        elif action == "report_found":
            await client.report_found(
                "42",
                "STUDENT",
                ReportFoundInput(
                    item_name="Black headphones",
                    category="ELECTRONICS",
                    description="Black wireless headphones in a fabric case",
                    location="Central Library",
                    event_date=date(2026, 8, 8),
                ),
            )
        elif action == "search_found_items":
            await client.search_found_items(
                "42",
                "STUDENT",
                SearchFoundItemsInput(category="ELECTRONICS", location="Library"),
            )
        elif action == "search_lost_items":
            await client.search_lost_items(
                "42",
                "STUDENT",
                SearchLostItemsInput(category="ELECTRONICS", location="Library"),
            )
        elif action == "get_item_detail":
            await client.get_item_detail("42", "STUDENT", GetItemDetailInput(report_id=7))
        else:
            await client.claim_item(
                "42",
                "STUDENT",
                ClaimItemInput(report_id=7, proof_description="A unique scratch is under the case"),
            )
    finally:
        await client.close()  # 清理 MockTransport 客户端

    # 断言请求的 HTTP 方法与后端路由
    request = captured[0]
    assert request.method == method
    assert request.url.path == path
    # 解 JWT 验证作用域：token 用后端共享密钥签名，必须只对当前动作授权
    token = request.headers["Authorization"].removeprefix("Bearer ")
    claims: dict[str, Any] = jwt.decode(
        token,
        settings.agent_backend_shared_secret,
        algorithms=["HS256"],
        audience="campus-api",
        issuer="lost-found-agent",
    )
    assert claims["sub"] == "42"  # 后端感知真实用户 id
    assert claims["role"] == "STUDENT"
    assert claims["intended_action"] == action  # 单动作最小权限
    assert claims["exp"] - claims["iat"] <= 60  # token 有效期很短，降低重放风险
    assert claims["jti"]  # 必须携带唯一 jti 防重放


async def test_report_lost_sends_image_keys_when_images_present(settings: Settings) -> None:
    """report_lost 携带图片时必须把 MinIO object key 以 imageKeys 传给后端。"""
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"id": 1})

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        await client.report_lost(
            "42",
            "STUDENT",
            ReportLostInput(
                item_name="Black headphones",
                category="ELECTRONICS",
                description="Black wireless headphones in a fabric case",
                location="Central Library",
                event_date=date(2026, 8, 8),
                images=["lost-found-staging/k.png"],  # 图片走 MinIO，只传 object key
            ),
        )
    finally:
        await client.close()

    body = json.loads(captured[0].read())
    assert body["imageKeys"] == ["lost-found-staging/k.png"]  # 驼峰命名对齐后端契约


async def test_search_maps_filters_to_query_parameters(settings: Settings) -> None:
    """搜索过滤条件正确映射为后端查询参数（驼峰、ISO 日期、分页 size）。"""
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"content": []})

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        # 传入 keyword/category/colour/location 与日期区间，全部应落到查询串
        await client.search_found_items(
            "8",
            "STUDENT",
            SearchFoundItemsInput(
                keyword="headphones",
                category="ELECTRONICS",
                colour="black",
                location="library",
                date_from=date(2026, 8, 1),
                date_to=date(2026, 8, 8),
            ),
        )
    finally:
        await client.close()

    params = captured[0].url.params
    assert params["category"] == "ELECTRONICS"  # 枚举值原样透传
    assert params["dateFrom"] == "2026-08-01"  # date 序列化为 ISO 字符串
    assert params["dateTo"] == "2026-08-08"
    assert params["size"] == "100"  # 默认分页大小


async def test_backend_domain_error_is_mapped_without_exposing_token(settings: Settings) -> None:
    """后端 409 业务错误映射为 BackendApiError，且错误信息不泄露 token。"""
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            409,
            json={"code": "CLAIM_ALREADY_EXISTS", "message": "Already submitted"},
        )

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        # 断言 claim_item 抛出的异常是 BackendApiError（pytest.raises 捕获）
        with pytest.raises(BackendApiError) as caught:
            await client.claim_item(
                "42",
                "STUDENT",
                ClaimItemInput(report_id=7, proof_description="A unique scratch is under the case"),
            )
    finally:
        await client.close()

    assert caught.value.status_code == 409  # 保留 HTTP 状态码
    assert caught.value.code == "CLAIM_ALREADY_EXISTS"  # 业务码可程序化判断
    assert str(caught.value) == "Already submitted"  # 面向用户的安全文案
    assert "Bearer" not in str(caught.value)  # 序列化结果绝不能包含 token


async def test_backend_error_field_is_preserved_for_safe_user_feedback(settings: Settings) -> None:
    """后端同时给 message 与 error 字段时，应优先采用可安全展示给用户的 error 文案。"""
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            409,
            # 后端这次只给 error 字段（用户友好文案），无 message
            json={
                "code": "CLAIM_ALREADY_EXISTS",
                "error": "You already have an active claim for this item",
            },
        )

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        with pytest.raises(BackendApiError) as caught:
            await client.claim_item(
                "42",
                "STUDENT",
                ClaimItemInput(report_id=7, proof_description="A unique scratch is under the case"),
            )
    finally:
        await client.close()

    assert caught.value.code == "CLAIM_ALREADY_EXISTS"
    assert str(caught.value) == "You already have an active claim for this item"  # 采用 error 文案


async def test_network_failure_is_mapped_to_service_unavailable(settings: Settings) -> None:
    """网络连接失败统一映射为 503 CAMPUS_API_UNAVAILABLE。"""
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection failed", request=request)  # 模拟后端不可达

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        with pytest.raises(BackendApiError) as caught:
            await client.get_item_detail("42", "STUDENT", GetItemDetailInput(report_id=9))
    finally:
        await client.close()

    assert caught.value.status_code == 503  # 网络故障统一视为服务不可用
    assert caught.value.code == "CAMPUS_API_UNAVAILABLE"
