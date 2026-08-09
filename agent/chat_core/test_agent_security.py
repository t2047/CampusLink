"""测试 — Agent 共享安全中间件（agent/shared/security.py）。

覆盖完整验证链（对齐 docs/communication-security.md）：
- HS256 模式：合法请求通过 / 缺头 / 签名错 / 过期 / nonce 重放 /
  jti!=X-Nonce / aud 不匹配 / token 过期 → 相应 401/403
- RS256 模式：配置 jwks_url 时走 PyJWKClient + jwt.decode(algorithms=["RS256"])，
  并通过 monkeypatch 验证调用路径与 claims 业务校验
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import sys
import time
import uuid
from pathlib import Path

import pytest

# 允许导入 agent/shared（与 mock_agent.py 相同的 path 处理方式）
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import jwt as pyjwt

from shared.security import AgentSecurityMiddleware, SecurityConfig

SECRET = "agent-test-secret"
BODY = b'{"message":"hi"}'


def sign(body: str, nonce: str, timestamp: int, secret: str = SECRET) -> str:
    message = f"{body}:{nonce}:{timestamp}"
    return hmac.new(secret.encode(), message.encode(), hashlib.sha256).hexdigest()


class FakeRequest:
    def __init__(self, headers: dict, body: bytes = BODY):
        self._headers = headers
        self._body = body

    @property
    def headers(self):
        return self._headers

    async def body(self):
        return self._body


def make_hs256_token(agent: str = "mail-agent", nonce: str = "", exp_delta: int = 60) -> str:
    now = int(time.time())
    return pyjwt.encode(
        {
            "sub": "u1",
            "role": "STUDENT",
            "aud": agent,
            "jti": nonce,
            "iat": now,
            "exp": now + exp_delta,
            "intended_action": "invoke",
        },
        SECRET,
        algorithm="HS256",
    )


def make_valid_request(agent: str = "mail-agent") -> FakeRequest:
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": f"Bearer {make_hs256_token(agent=agent, nonce=nonce)}",
    }
    return FakeRequest(headers)


def make_security(agent: str = "mail-agent", jwks_url: str = "") -> AgentSecurityMiddleware:
    return AgentSecurityMiddleware(
        SecurityConfig(agent_name=agent, shared_secret=SECRET, jwks_url=jwks_url)
    )


# ──────────────────────────────────────────────────────────────────────
# HS256 模式
# ──────────────────────────────────────────────────────────────────────

def test_valid_request_passes():
    security = make_security()
    verified = asyncio.run(security.verify(make_valid_request()))
    assert verified.user_id == "u1"
    assert verified.user_role == "STUDENT"
    assert verified.intended_action == "invoke"
    assert verified.claims["aud"] == "mail-agent"


def test_missing_headers_rejected():
    security = make_security()
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest({})))
    assert exc.value.status_code == 401


def test_bad_signature_rejected():
    security = make_security()
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": "deadbeef",
        "Authorization": f"Bearer {make_hs256_token(nonce=nonce)}",
    }
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest(headers)))
    assert exc.value.status_code == 401


def test_expired_timestamp_rejected():
    security = make_security()
    nonce = str(uuid.uuid4())
    ts = int(time.time()) - 100
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": f"Bearer {make_hs256_token(nonce=nonce)}",
    }
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest(headers)))
    assert exc.value.status_code == 401


def test_nonce_replay_rejected():
    security = make_security()
    request = make_valid_request()
    asyncio.run(security.verify(request))
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(request))
    assert exc.value.status_code == 401


def test_token_jti_mismatch_nonce_rejected():
    """token.jti 必须等于 X-Nonce（防重放绑定），否则 401。"""
    security = make_security()
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    # token 的 jti 用另一个值
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": f"Bearer {make_hs256_token(nonce='different-nonce')}",
    }
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest(headers)))
    assert exc.value.status_code == 401


def test_wrong_audience_rejected():
    """token.aud 必须等于 agent_name，否则 403（防跨 Agent 滥用）。"""
    security = make_security(agent="mail-agent")
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": f"Bearer {make_hs256_token(agent='facility-agent', nonce=nonce)}",
    }
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest(headers)))
    assert exc.value.status_code == 403


def test_expired_token_rejected():
    security = make_security()
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": f"Bearer {make_hs256_token(nonce=nonce, exp_delta=-60)}",
    }
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest(headers)))
    assert exc.value.status_code == 401


# ──────────────────────────────────────────────────────────────────────
# RS256 模式（monkeypatch PyJWKClient / jwt.decode，避免依赖 cryptography）
# ──────────────────────────────────────────────────────────────────────

class FakeSigningKey:
    key = "fake-rsa-public-key"


class FakeJwksClient:
    last_url: str | None = None

    def __init__(self, url: str):
        self.url = url
        FakeJwksClient.last_url = url

    def get_signing_key_from_jwt(self, token: str) -> FakeSigningKey:
        return FakeSigningKey()


def _claims_for(nonce: str, aud: str = "mail-agent") -> dict:
    return {
        "sub": "u1",
        "role": "STUDENT",
        "aud": aud,
        "jti": nonce,
        "exp": int(time.time()) + 60,
        "intended_action": "invoke",
    }


def test_rs256_mode_uses_jwks_endpoint(monkeypatch):
    captured: dict = {}

    def fake_decode(token, key, algorithms=None, options=None):
        captured["key"] = key
        captured["algorithms"] = algorithms
        captured["options"] = options
        return _claims_for(captured["nonce"])

    monkeypatch.setattr(pyjwt, "PyJWKClient", FakeJwksClient)
    monkeypatch.setattr(pyjwt, "decode", fake_decode)

    security = make_security(jwks_url="http://token-service/.well-known/jwks.json")
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    captured["nonce"] = nonce
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": "Bearer rs256.token",
    }
    verified = asyncio.run(security.verify(FakeRequest(headers)))

    # 走 RS256：PyJWKClient 用 jwks_url，decode 限定 RS256 + 必需 claims
    assert FakeJwksClient.last_url == "http://token-service/.well-known/jwks.json"
    assert captured["key"] == "fake-rsa-public-key"
    assert captured["algorithms"] == ["RS256"]
    assert captured["options"] == {"require": ["sub", "aud", "exp", "jti"]}
    assert verified.user_id == "u1"


def test_rs256_mode_rejects_wrong_audience(monkeypatch):
    monkeypatch.setattr(pyjwt, "PyJWKClient", FakeJwksClient)

    def fake_decode(token, key, algorithms=None, options=None):
        return _claims_for("nonce", aud="facility-agent")

    monkeypatch.setattr(pyjwt, "decode", fake_decode)

    security = make_security(agent="mail-agent", jwks_url="http://token-service/jwks.json")
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(BODY.decode(), nonce, ts),
        "Authorization": "Bearer rs256.token",
    }
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(FakeRequest(headers)))
    assert exc.value.status_code == 403
