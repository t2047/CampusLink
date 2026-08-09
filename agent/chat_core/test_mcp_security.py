"""测试 — Agent MCP Server 安全中间件（mcp_servers/security.py）。

覆盖 MCP 层安全模型（Sprint 3，RS256 单一模式）：
- TokenVerifier：合法 RS256 token / 错误 aud / 过期 / 缺失 / 未配置 JWKS
- McpSecurityMiddleware：无 token 401、合法 token 放行并注入身份、时间窗口

HS256 联调回退已移除（2026-08-08），全部走 JWKS 拉公钥验签。
测试用 FakeJWKSClient 替换 PyJWKClient（避免真实 HTTP 依赖），
验签仍走真实的 jwt.decode RS256 路径。
"""

from __future__ import annotations

import sys
import time
from pathlib import Path
from types import SimpleNamespace

_ROOT = Path(__file__).resolve().parents[1]  # agent/
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import jwt as pyjwt
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from mcp_servers.security import McpSecurityMiddleware, TokenVerifier

# 模块级 RSA 密钥对（与后端 Token Service 每次启动随机生成的行为对齐）
_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUBLIC_PEM = (
    _PRIVATE_KEY.public_key()
    .public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    .decode()
)

JWKS_URL = "http://test-local/jwks.json"


class FakeJWKSClient:
    """替换 PyJWKClient：按 kid 返回固定公钥（不发起真实 HTTP）。"""

    def __init__(self, url: str):
        self.url = url

    def get_signing_key_from_jwt(self, token: str):
        return SimpleNamespace(key=_PUBLIC_PEM)


def make_token(agent: str = "mail-agent", exp_delta: int = 60) -> str:
    now = int(time.time())
    return pyjwt.encode(
        {
            "sub": "u1",
            "role": "STUDENT",
            "aud": agent,
            "iat": now,
            "exp": now + exp_delta,
            "intended_action": "invoke",
        },
        _PRIVATE_KEY,
        algorithm="RS256",
        headers={"kid": "test-kid"},
    )


def make_app() -> FastAPI:
    app = FastAPI()
    app.add_middleware(McpSecurityMiddleware, agent_name="mail-agent")

    @app.get("/ping")
    async def ping(request):
        return {
            "ok": True,
            "user": request.state.user_id,
            "role": request.state.user_role,
        }

    return app


def use_rs256(monkeypatch) -> None:
    """切换到 RS256 模式：配置 JWKS URL + 替换 PyJWKClient 为假实现。"""
    monkeypatch.setenv("TOKEN_SERVICE_JWKS_URL", JWKS_URL)
    monkeypatch.setattr("mcp_servers.security.jwt.PyJWKClient", FakeJWKSClient)


# ──────────────────────────────────────────────────────────────────────
# TokenVerifier
# ──────────────────────────────────────────────────────────────────────

def test_token_verifier_accepts_valid(monkeypatch):
    use_rs256(monkeypatch)
    verifier = TokenVerifier("mail-agent")
    claims = verifier.verify_token(make_token())
    assert claims["sub"] == "u1"
    assert claims["role"] == "STUDENT"


def test_token_verifier_rejects_wrong_audience(monkeypatch):
    use_rs256(monkeypatch)
    verifier = TokenVerifier("mail-agent")
    with pytest.raises(ValueError):
        verifier.verify_token(make_token(agent="facility-agent"))


def test_token_verifier_rejects_expired(monkeypatch):
    use_rs256(monkeypatch)
    verifier = TokenVerifier("mail-agent")
    with pytest.raises(Exception):
        verifier.verify_token(make_token(exp_delta=-60))


def test_token_verifier_rejects_missing(monkeypatch):
    use_rs256(monkeypatch)
    verifier = TokenVerifier("mail-agent")
    with pytest.raises(ValueError):
        verifier.verify_token("")


def test_token_verifier_requires_jwks_url(monkeypatch):
    """未配置 TOKEN_SERVICE_JWKS_URL → 明确报错（fail-closed，无 HS256 回退）。"""
    monkeypatch.delenv("TOKEN_SERVICE_JWKS_URL", raising=False)
    verifier = TokenVerifier("mail-agent")
    with pytest.raises(ValueError, match="TOKEN_SERVICE_JWKS_URL"):
        verifier.verify_token(make_token())


# ──────────────────────────────────────────────────────────────────────
# McpSecurityMiddleware（ASGI 集成）
# ──────────────────────────────────────────────────────────────────────

def test_middleware_rejects_without_token(monkeypatch):
    use_rs256(monkeypatch)
    client = TestClient(make_app())
    response = client.get("/ping")
    assert response.status_code == 401


def test_middleware_accepts_valid_token(monkeypatch):
    use_rs256(monkeypatch)
    client = TestClient(make_app())
    response = client.get(
        "/ping",
        headers={"Authorization": f"Bearer {make_token()}"},
    )
    assert response.status_code == 200
    assert response.json()["user"] == "u1"
    assert response.json()["role"] == "STUDENT"


def test_middleware_rejects_expired_timestamp(monkeypatch):
    use_rs256(monkeypatch)
    client = TestClient(make_app())
    stale_ts = int(time.time()) - 3600
    response = client.get(
        "/ping",
        headers={
            "Authorization": f"Bearer {make_token()}",
            "X-Timestamp": str(stale_ts),
        },
    )
    assert response.status_code == 401
