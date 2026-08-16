"""测试 — AgentClient：RS256 Delegation Token 兑换 + jti==nonce 绑定 + 联调回退。

验证：
1. get_delegation_token 正确构造 Token Service 请求（URL/body/HMAC 头可复验）
2. Token Service 未配置 / 服务端错误 → 返回 None（触发回退）
3. 本地 HS256 回退 token 的 jti == nonce
4. _obtain_delegation_token：RS256 优先，失败回退 HS256
5. invoke_agent 集成：发往 Agent 的 X-Nonce == token 的 jti（防重放一致性）
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json

import httpx

from orchestration.mcp.client import AgentClient, _describe_mcp_failure
from orchestration.mcp.registry import AgentConfig, ServiceRegistry

SECRET = "test-secret"


def make_registry(token_service_url: str | None = "http://backend:8080") -> ServiceRegistry:
    reg = ServiceRegistry()
    reg.shared_secret = SECRET
    reg.token_service_url = token_service_url
    # 测试默认联调模式：Token Service 不可用时允许回退本地 HS256
    reg.agents["mail-agent"] = AgentConfig(name="mail-agent", url="http://agent:8081", timeout_ms=30000)
    reg.agents["lost-found-agent"] = AgentConfig(
        name="lost-found-agent",
        url="http://lost-found-agent:8083",
        mcp_url="http://lost-found-mcp:8085/mcp/",
        timeout_ms=30000,
    )
    reg.agents["utility-tools"] = AgentConfig(name="utility-tools", url="http://util:8090", timeout_ms=5000)
    reg.utility_url = "http://util:8090"
    reg.utility_mcp_url = "http://util:8090/mcp/"
    return reg


def verify_hmac(headers: dict, body: dict, secret: str = SECRET) -> None:
    """用同一密钥复验请求签名（与 AgentClient._sign 相同格式）。"""
    nonce = headers["X-Nonce"]
    ts = headers["X-Timestamp"]
    body_str = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    expected = hmac.new(secret.encode(), f"{body_str}:{nonce}:{ts}".encode(), hashlib.sha256).hexdigest()
    assert headers["X-Signature"] == expected


# ──────────────────────────────────────────────────────────────────────
# 1. get_delegation_token：请求构造与响应解析
# ──────────────────────────────────────────────────────────────────────


def test_get_delegation_token_requests_rs256_exchange():
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "token": "rs256.token.abc",
                "expiresInSeconds": 30,
                "algorithm": "RS256",
                "kid": "k1",
            },
        )

    async def scenario() -> str | None:
        client = AgentClient(
            registry=make_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        )
        try:
            return await client.get_delegation_token(
                user_id="u1", role="STUDENT", target_agent="mail-agent", jti="nonce-1"
            )
        finally:
            await client.close()

    token = asyncio.run(scenario())
    assert token == "rs256.token.abc"
    assert captured["url"] == "http://backend:8080/internal/token/exchange"
    assert captured["body"] == {
        "userId": "u1",
        "role": "STUDENT",
        "targetAgent": "mail-agent",
        "intendedAction": "invoke",
        "jti": "nonce-1",
    }
    verify_hmac(captured["headers"], captured["body"])


def test_get_delegation_token_without_token_service_returns_none():
    async def scenario() -> str | None:
        client = AgentClient(registry=make_registry(token_service_url=None))
        try:
            return await client.get_delegation_token(
                user_id="u1", role="STUDENT", target_agent="mail-agent", jti="n"
            )
        finally:
            await client.close()

    assert asyncio.run(scenario()) is None


def test_get_delegation_token_server_error_returns_none():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    async def scenario() -> str | None:
        client = AgentClient(
            registry=make_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        )
        try:
            return await client.get_delegation_token(
                user_id="u1", role="STUDENT", target_agent="mail-agent", jti="n"
            )
        finally:
            await client.close()

    assert asyncio.run(scenario()) is None


# ──────────────────────────────────────────────────────────────────────
# 2. 本地 HS256 回退：jti 绑定（MCP 层不要求 X-Nonce，jti 可任意）
# ──────────────────────────────────────────────────────────────────────


def test_issue_local_token_removed():
    """HS256 本地签发已移除（2026-08-08）：AgentClient 不再有该方法。"""
    assert not hasattr(AgentClient, "issue_local_delegation_token")


# ──────────────────────────────────────────────────────────────────────
# 3. _obtain_delegation_token：RS256 兑换优先，失败 fail-closed（返回 None）
# ──────────────────────────────────────────────────────────────────────


def test_obtain_token_prefers_rs256_then_fail_closed():
    def handler_ok(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"token": "rs256.token"})

    async def scenario_ok() -> str | None:
        client = AgentClient(
            registry=make_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler_ok)),
        )
        try:
            return await client._obtain_delegation_token("u1", "STUDENT", "mail-agent", "nonce-x")
        finally:
            await client.close()

    assert asyncio.run(scenario_ok()) == "rs256.token"

    def handler_fail(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="token service down")

    async def scenario_fail() -> str | None:
        client = AgentClient(
            registry=make_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler_fail)),
        )
        try:
            return await client._obtain_delegation_token("u1", "STUDENT", "mail-agent", "nonce-y")
        finally:
            await client.close()

    # fail-closed：Token Service 不可用时拒绝调用，不再回退本地 HS256 签发
    assert asyncio.run(scenario_fail()) is None


# ──────────────────────────────────────────────────────────────────────
# 5. _parse_mcp_result：MCP 工具返回解析（text JSON 主路径 + structured 回退）
# ──────────────────────────────────────────────────────────────────────


class FakeTextContent:
    type = "text"

    def __init__(self, text: str):
        self.text = text


class FakeCallResult:
    def __init__(self, content=None, structured_content=None, isError=False):
        self.content = content or []
        self.structured_content = structured_content
        self.isError = isError


def test_describe_mcp_failure_transport_error():
    """MCP 服务未启动（连接层失败）→ 明确英文报错：服务名 + URL + 运行提示。"""
    err = httpx.ConnectError("connection refused")
    msg = _describe_mcp_failure(err, "utility-tools", "http://localhost:8090/mcp/")
    assert "MCP service 'utility-tools' is unreachable at http://localhost:8090/mcp/" in msg
    assert "Please ensure the service is running" in msg


def test_describe_mcp_failure_non_transport():
    """非传输层异常 → 保留原始原因文本。"""
    assert _describe_mcp_failure(ValueError("bad thing"), "x", "u") == "bad thing"


def test_invoke_utility_mcp_unreachable_error(monkeypatch):
    """invoke_utility 在 MCP 服务不可达时返回英文明确报错（不暴露裸异常）。"""

    async def scenario():
        client = AgentClient(registry=make_registry())

        async def boom(*args, **kwargs):
            raise httpx.ConnectError("connection refused")

        monkeypatch.setattr(client, "_call_mcp_tool", boom)
        try:
            return await client.invoke_utility("get_current_time", {}, delegation_token="test-token")
        finally:
            await client.close()

    result = asyncio.run(scenario())
    assert result["status"] == "failed"
    assert "is unreachable" in result["error"]
    assert "utility-tools" in result["error"]


def test_lost_found_initial_invoke_retries_one_transport_failure(monkeypatch):
    """确认前的 L&F 调用遇到连接瞬断时重试一次并返回成功结果。"""

    async def scenario():
        client = AgentClient(registry=make_registry())
        calls = 0

        async def flaky(*args, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise httpx.RemoteProtocolError("peer closed connection")
            return {"response": "请确认报失信息", "status": "needs_confirmation"}

        monkeypatch.setattr(client, "_call_mcp_tool", flaky)
        try:
            result = await client.invoke_agent(
                "lost-found-agent",
                "I lost black shoes",
                "1",
                "STUDENT",
                delegation_token="test-token",
            )
            return calls, result
        finally:
            await client.close()

    calls, result = asyncio.run(scenario())
    assert calls == 2
    assert result["status"] == "needs_confirmation"


def test_lost_found_confirmed_invoke_does_not_retry_transport_failure(monkeypatch):
    """确认后的写操作发生连接异常时不重试，防止重复创建记录。"""

    async def scenario():
        client = AgentClient(registry=make_registry())
        calls = 0

        async def broken(*args, **kwargs):
            nonlocal calls
            calls += 1
            raise httpx.RemoteProtocolError("peer closed connection")

        monkeypatch.setattr(client, "_call_mcp_tool", broken)
        try:
            result = await client.invoke_agent(
                "lost-found-agent",
                "confirm",
                "1",
                "STUDENT",
                delegation_token="test-token",
                confirmed=True,
                confirmation_id="confirmation-1",
            )
            return calls, result
        finally:
            await client.close()

    calls, result = asyncio.run(scenario())
    assert calls == 1
    assert result["status"] == "failed"


def test_parse_mcp_result_text_json():
    """主路径：text content 是 JSON 字符串 → 解析为 dict。"""
    client = AgentClient(registry=make_registry())
    result = FakeCallResult(content=[FakeTextContent(json.dumps({"response": "ok", "status": "completed"}))])
    parsed = client._parse_mcp_result(result)
    assert parsed == {"response": "ok", "status": "completed"}


def test_parse_mcp_result_text_non_json():
    """text content 非 JSON → 包装为 response 字段。"""
    client = AgentClient(registry=make_registry())
    parsed = client._parse_mcp_result(FakeCallResult(content=[FakeTextContent("你好")]))
    assert parsed["response"] == "你好"
    assert parsed["status"] == "completed"


def test_parse_mcp_result_structured_list():
    """回退：structured_content（list）中含业务 dict 的项。"""
    client = AgentClient(registry=make_registry())
    result = FakeCallResult(structured_content=[{"response": "结构化结果", "status": "completed"}])
    parsed = client._parse_mcp_result(result)
    assert parsed["response"] == "结构化结果"


def test_parse_mcp_result_is_error():
    client = AgentClient(registry=make_registry())
    parsed = client._parse_mcp_result(FakeCallResult(isError=True))
    assert parsed["status"] == "failed"


def test_parse_mcp_result_empty():
    client = AgentClient(registry=make_registry())
    parsed = client._parse_mcp_result(FakeCallResult())
    assert parsed == {"response": "", "status": "completed"}


def test_normalize_result_preserves_match_results():
    client = AgentClient(registry=make_registry())
    matches = [{"item_id": "7", "report_type": "FOUND", "match_score": 0.88}]

    normalized = client._normalize_result(
        {"response": "找到候选", "status": "match_found", "match_results": matches},
        "lost-found-agent",
    )

    assert normalized["match_results"] == matches
