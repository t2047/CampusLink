"""编排层入站安全中间件 — 校验 Chat Backend 转发的请求。

验证链（对齐通信安全说明文档）：
1. X-Signature HMAC 签名校验（与 Chat Backend OrchestrationClient.sign 一致）
2. X-Nonce + X-Timestamp 防重放
3. X-Trace-Id 透传（可观测性）

说明：
- 本中间件校验的是「编排层 ← Chat Backend」这一跳的共享密钥 HMAC；
- 「编排层 → Agent」的 Delegation Token 验签由各 Agent 的
  agent/shared/security.py 负责，两跳分离，互不混淆。
"""

from __future__ import annotations

import hashlib
import hmac
import time
from dataclasses import dataclass, field
from typing import Optional

from fastapi import HTTPException, Request

from ..mcp.registry import ServiceRegistry


@dataclass
class VerifiedInbound:
    """编排层入站验证通过后的安全上下文。"""

    trace_id: str
    user_id: Optional[str] = None
    role: Optional[str] = None


class OrchestrationInboundSecurity:
    """编排层入站安全中间件。"""

    def __init__(self, registry: ServiceRegistry):
        self.registry = registry
        self._nonce_cache: dict[str, float] = {}
        # Sprint 2: 替换为 Redis SETNX 分布式去重

    async def verify(self, request: Request) -> VerifiedInbound:
        """校验入站请求，失败抛出 HTTPException。"""
        # ── Step 1: 提取安全 Headers ──
        nonce = request.headers.get("X-Nonce")
        timestamp_str = request.headers.get("X-Timestamp")
        signature = request.headers.get("X-Signature")
        trace_id = request.headers.get("X-Trace-Id", "")

        if not all([nonce, timestamp_str, signature]):
            raise HTTPException(status_code=401, detail="missing security headers")

        try:
            timestamp = int(timestamp_str)
        except ValueError:
            raise HTTPException(status_code=401, detail="invalid timestamp")

        # ── Step 2: 防重放（时间窗口 + Nonce 一次性）──
        now = int(time.time())
        if abs(now - timestamp) > self.registry.time_window_seconds:
            raise HTTPException(status_code=401, detail="request expired — possible replay")
        if nonce in self._nonce_cache:
            raise HTTPException(status_code=401, detail="nonce reused — replay detected")
        self._nonce_cache[nonce] = now
        self._nonce_cache = {
            n: t for n, t in self._nonce_cache.items()
            if now - t < 60  # 60s 后清理
        }

        # ── Step 3: HMAC 签名校验（防篡改）──
        body = await request.body()
        body_str = body.decode("utf-8", errors="replace")
        expected_sig = self._sign(body_str, nonce, timestamp)
        if not hmac.compare_digest(expected_sig, signature):
            raise HTTPException(status_code=401, detail="signature mismatch")

        return VerifiedInbound(
            trace_id=trace_id,
            user_id=request.headers.get("X-User-Id"),
            role=request.headers.get("X-User-Role"),
        )

    def _sign(self, body: str, nonce: str, timestamp: int) -> str:
        """HMAC-SHA256 签名（与 Chat Backend 保持一致）。"""
        message = f"{body}:{nonce}:{timestamp}"
        secret = (self.registry.shared_secret or "").encode()
        return hmac.new(secret, message.encode(), hashlib.sha256).hexdigest()
