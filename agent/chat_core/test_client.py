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
import time
import uuid

import httpx
import jwt as pyjwt

from orchestration.mcp.client import AgentClient
from orchestration.mcp.registry import AgentConfig, ServiceRegistry

SECRET = "test-secret"


def make_registry(token_service_url: str | None = "http://backend:8080") -> ServiceRegistry:
    reg = ServiceRegistry()
    reg.shared_secret = SECRET
    reg.token_service_url = token_service_url
    # 测试默认联调模式：Token Service 不可用时允许回退本地 HS256
    reg.allow_hs256_fallback = True
    reg.agents["mail-agent"] = AgentConfig(
        name="mail-agent", url="http://agent:8081", timeout_ms=30000
    )
    reg.agents["utility-tools"] = AgentConfig(
        name="utility-tools", url="http://util:8090", timeout_ms=5000
    )
    reg.utility_url = "http://util:8090"
    return reg


def verify_hmac(headers: dict, body: dict, secret: str = SECRET) -> None:
    """用同一密钥复验请求签名（与 AgentClient._sign 相同格式）。"""
    nonce = headers["X-Nonce"]
    ts = headers["X-Timestamp"]
    body_str = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    expected = hmac.new(
        secret.encode(), f"{body_str}:{nonce}:{ts}".encode(), hashlib.sha256
    ).hexdigest()
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
# 2. 本地 HS256 回退：jti == nonce
# ──────────────────────────────────────────────────────────────────────

def test_issue_local_token_jti_equals_nonce():
    client = AgentClient(registry=make_registry())
    nonce = "fixed-nonce-123"
    token = client.issue_local_delegation_token(
        user_id="u1", role="STUDENT", target_agent="mail-agent", nonce=nonce
    )
    payload = pyjwt.decode(token, SECRET, algorithms=["HS256"])
    assert payload["jti"] == nonce
    assert payload["aud"] == "mail-agent"
    assert payload["sub"] == "u1"
    assert payload["intended_action"] == "invoke"


# ──────────────────────────────────────────────────────────────────────
# 3. _obtain_delegation_token：RS256 优先，失败回退
# ──────────────────────────────────────────────────────────────────────

def test_obtain_token_prefers_rs256_then_fallback_hs256():
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

    async def scenario_fallback() -> dict:
        client = AgentClient(
            registry=make_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler_fail)),
        )
        try:
            token = await client._obtain_delegation_token("u1", "STUDENT", "mail-agent", "nonce-y")
            return {"token": token}
        finally:
            await client.close()

    result = asyncio.run(scenario_fallback())
    payload = pyjwt.decode(result["token"], SECRET, algorithms=["HS256"])
    assert payload["jti"] == "nonce-y"
    assert payload["aud"] == "mail-agent"


def test_obtain_token_fail_closed_when_fallback_disabled():
    """ALLOW_HS256_FALLBACK=false 时 Token Service 不可用 → 返回 None（拒绝调用）。"""

    def handler_fail(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="token service down")

    async def scenario() -> str | None:
        reg = make_registry()
        reg.allow_hs256_fallback = False
        client = AgentClient(
            registry=reg,
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler_fail)),
        )
        try:
            return await client._obtain_delegation_token("u1", "STUDENT", "mail-agent", "nonce-z")
        finally:
            await client.close()

    assert asyncio.run(scenario()) is None


# ──────────────────────────────────────────────────────────────────────
# 4. invoke_agent 集成：X-Nonce == token 的 jti
# ──────────────────────────────────────────────────────────────────────

def test_invoke_agent_binds_nonce_to_token_jti():
    agent_calls: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/internal/token/exchange":
            payload = json.loads(request.content)
            # 测试用 HS256 代替 RS256（仅便于本地验签，逻辑等价）
            token = pyjwt.encode(
                {
                    "sub": payload["userId"],
                    "role": payload["role"],
                    "aud": payload["targetAgent"],
                    "jti": payload["jti"],
                    "iat": int(time.time()),
                    "exp": int(time.time()) + 30,
                    "intended_action": "invoke",
                },
                "rs256-test-secret",
                algorithm="HS256",
            )
            return httpx.Response(200, json={"token": token})
        if request.url.path == "/agent/invoke":
            agent_calls["headers"] = request.headers
            agent_calls["body"] = json.loads(request.content)
            return httpx.Response(200, json={"response": "ok", "status": "completed"})
        return httpx.Response(404)

    async def scenario() -> dict:
        client = AgentClient(
            registry=make_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        )
        try:
            return await client.invoke_agent(
                agent_name="mail-agent",
                message="帮我找张三的邮件",
                user_id="u1",
                user_role="STUDENT",
                trace_id="t1",
            )
        finally:
            await client.close()

    result = asyncio.run(scenario())
    assert result["status"] == "completed"

    token = agent_calls["headers"]["Authorization"].removeprefix("Bearer ")
    payload = pyjwt.decode(token, "rs256-test-secret", algorithms=["HS256"])
    # 核心一致性：调用 Agent 时的 X-Nonce == token 内 jti
    assert payload["jti"] == agent_calls["headers"]["X-Nonce"]
    assert payload["aud"] == "mail-agent"
    assert payload["sub"] == "u1"
    verify_hmac(agent_calls["headers"], agent_calls["body"])
