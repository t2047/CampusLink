"""测试 — 编排层入站安全中间件（HMAC + Nonce + Timestamp）。

验证：缺 Header / 签名错误 / 时间窗口过期 / Nonce 重复 → 401；
合法请求 → 返回 VerifiedInbound。
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import time
import uuid

import pytest

from orchestration.mcp.registry import ServiceRegistry
from orchestration.security.middleware import OrchestrationInboundSecurity


def make_registry() -> ServiceRegistry:
    reg = ServiceRegistry()
    reg.shared_secret = "test-secret"
    reg.time_window_seconds = 30
    return reg


def sign(body: str, nonce: str, timestamp: int, secret: str = "test-secret") -> str:
    message = f"{body}:{nonce}:{timestamp}"
    return hmac.new(secret.encode(), message.encode(), hashlib.sha256).hexdigest()


class FakeRequest:
    def __init__(self, headers: dict, body: bytes):
        self._headers = headers
        self._body = body

    @property
    def headers(self):
        return self._headers

    async def body(self):
        return self._body


def make_valid_request(body: str = '{"message":"hi"}'):
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(body, nonce, ts),
        "X-Trace-Id": "trace-1",
    }
    return FakeRequest(headers, body.encode())


def test_valid_request_passes():
    security = OrchestrationInboundSecurity(make_registry())
    request = make_valid_request()
    verified = asyncio.run(security.verify(request))
    assert verified.trace_id == "trace-1"


def test_missing_headers_rejected():
    security = OrchestrationInboundSecurity(make_registry())
    request = FakeRequest({}, b"{}")
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(request))
    assert exc.value.status_code == 401


def test_bad_signature_rejected():
    security = OrchestrationInboundSecurity(make_registry())
    body = '{"message":"hi"}'
    nonce = str(uuid.uuid4())
    ts = int(time.time())
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": "deadbeef",
        "X-Trace-Id": "t",
    }
    request = FakeRequest(headers, body.encode())
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(request))
    assert exc.value.status_code == 401


def test_expired_timestamp_rejected():
    security = OrchestrationInboundSecurity(make_registry())
    body = '{"message":"hi"}'
    nonce = str(uuid.uuid4())
    ts = int(time.time()) - 100
    headers = {
        "X-Nonce": nonce,
        "X-Timestamp": str(ts),
        "X-Signature": sign(body, nonce, ts),
    }
    request = FakeRequest(headers, body.encode())
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(request))
    assert exc.value.status_code == 401


def test_nonce_replay_rejected():
    security = OrchestrationInboundSecurity(make_registry())
    request = make_valid_request()
    asyncio.run(security.verify(request))
    with pytest.raises(Exception) as exc:
        asyncio.run(security.verify(request))
    assert exc.value.status_code == 401
