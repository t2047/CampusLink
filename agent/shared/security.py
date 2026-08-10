"""Agent 共享安全中间件 — 所有 Agent MCP Server 复用。

验证链（对齐通信安全说明文档）：
1. 传输层检查（HTTPS / mTLS，开发期可跳过）
2. HMAC 请求签名验证（防篡改）
3. Nonce + Timestamp 防重放
4. Delegation Token 验签（RS256 / HS256 双模式）
5. Claims 业务校验（aud / jti / intended_action）

用法（FastAPI）：
    security = AgentSecurityMiddleware(SecurityConfig(
        agent_name="mail-agent",
        shared_secret=os.environ["AGENT_SHARED_SECRET"],
        jwks_url=os.environ["TOKEN_SERVICE_JWKS_URL"],   # Sprint 3+ RS256
    ))

    @app.post("/agent/invoke")
    async def invoke(request: Request):
        verified = await security.verify(request)
        # verified.user_id 来自签名 Token，可信
"""

from __future__ import annotations

import hashlib
import hmac
import os
import time
from dataclasses import dataclass, field
from typing import Any, Optional

from fastapi import HTTPException, Request

try:
    import jwt
    HAS_PYJWT = True
except ImportError:  # pragma: no cover
    HAS_PYJWT = False


@dataclass
class SecurityConfig:
    """安全中间件配置。"""

    agent_name: str                       # "mail-agent"
    shared_secret: str = ""               # HS256 模式密钥（从环境变量读取）
    jwks_url: str = ""                    # RS256 模式公钥端点（Sprint 3+）
    time_window_seconds: int = 30
    require_https: bool = False           # 开发期 false；生产 true
    nonce_ttl_seconds: int = 60           # 防重放窗口


@dataclass
class VerifiedRequest:
    """验证通过后的安全上下文（注入业务层）。"""

    user_id: str
    user_role: str
    intended_action: str
    nonce: str
    trace_id: Optional[str] = None
    claims: dict[str, Any] = field(default_factory=dict)


class AgentSecurityMiddleware:
    """Agent 端完整安全验证链。"""

    def __init__(self, config: SecurityConfig):
        self.config = config
        self._nonce_cache: dict[str, float] = {}
        # Sprint 2: 替换为 Redis SETNX 分布式去重

    async def verify(self, request: Request) -> VerifiedRequest:
        """完整验证链，任何一步失败抛出 HTTPException。"""
        # ── Step 1: 传输层检查 ──
        if self.config.require_https and request.url.scheme != "https":
            raise HTTPException(status_code=426, detail="HTTPS required")

        # ── Step 2: 提取安全 Headers ──
        body = await request.body()
        body_str = body.decode("utf-8", errors="replace")
        nonce = request.headers.get("X-Nonce")
        timestamp_str = request.headers.get("X-Timestamp")
        signature = request.headers.get("X-Signature")
        auth_header = request.headers.get("Authorization", "")

        if not all([nonce, timestamp_str, signature, auth_header]):
            raise HTTPException(status_code=401, detail="missing security headers")

        try:
            timestamp = int(timestamp_str)
        except ValueError:
            raise HTTPException(status_code=401, detail="invalid timestamp")

        token = auth_header.removeprefix("Bearer ").strip()
        if not token:
            raise HTTPException(status_code=401, detail="missing bearer token")

        # ── Step 3: 防重放（Nonce + Timestamp）──
        self._check_replay(nonce, timestamp)

        # ── Step 4: HMAC 请求签名验证（防篡改）──
        expected_sig = self._sign(body_str, nonce, timestamp)
        if not hmac.compare_digest(expected_sig, signature):
            raise HTTPException(status_code=401, detail="signature mismatch")

        # ── Step 5: Delegation Token 验签 ──
        claims = self._verify_token(token)

        # ── Step 6: Claims 业务校验 ──
        if claims.get("aud") != self.config.agent_name:
            raise HTTPException(
                status_code=403,
                detail=f"token for '{claims.get('aud')}', not '{self.config.agent_name}'",
            )
        if claims.get("jti") != nonce:
            raise HTTPException(status_code=401, detail="nonce mismatch")

        # ── Step 7: 返回可信上下文 ──
        return VerifiedRequest(
            user_id=claims.get("sub", ""),
            user_role=claims.get("role", "UNKNOWN"),
            intended_action=claims.get("intended_action", ""),
            nonce=nonce,
            trace_id=request.headers.get("X-Trace-Id"),
            claims=claims,
        )

    # ──────────────────────────────────────────────────────────────
    # 内部方法
    # ──────────────────────────────────────────────────────────────

    def _sign(self, body: str, nonce: str, timestamp: int) -> str:
        """HMAC-SHA256 签名（与编排层 AgentClient._sign 一致）。"""
        message = f"{body}:{nonce}:{timestamp}"
        secret = self.config.shared_secret.encode()
        return hmac.new(secret, message.encode(), hashlib.sha256).hexdigest()

    def _check_replay(self, nonce: str, timestamp: int) -> None:
        """防重放：时间窗口 + Nonce 一次性。"""
        now = int(time.time())
        if abs(now - timestamp) > self.config.time_window_seconds:
            raise HTTPException(status_code=401, detail="request expired — possible replay")

        if nonce in self._nonce_cache:
            raise HTTPException(status_code=401, detail="nonce reused — replay detected")

        self._nonce_cache[nonce] = now
        # 清理过期 nonce，防止内存无限增长
        self._nonce_cache = {
            n: t for n, t in self._nonce_cache.items()
            if now - t < self.config.nonce_ttl_seconds
        }

    def _verify_token(self, token: str) -> dict[str, Any]:
        """Delegation Token 验签：RS256（Sprint 3+，走 JWKS）或 HS256（当前）。"""
        if not HAS_PYJWT:
            raise HTTPException(status_code=500, detail="PyJWT not installed")

        try:
            # ── RS256 模式：从 JWKS 端点取公钥 ──
            if self.config.jwks_url:
                jwks_client = jwt.PyJWKClient(self.config.jwks_url)
                signing_key = jwks_client.get_signing_key_from_jwt(token)
                return jwt.decode(
                    token,
                    signing_key.key,
                    algorithms=["RS256"],
                    options={
                        "require": ["sub", "aud", "exp", "jti"],
                        "verify_aud": False,
                    },
                )

            # ── HS256 模式：共享密钥验签（Sprint 1-2）──
            return jwt.decode(
                token,
                self.config.shared_secret,
                algorithms=["HS256"],
                options={
                    "require": ["sub", "aud", "exp", "jti"],
                    "verify_aud": False,
                },
            )

        except jwt.ExpiredSignatureError:
            raise HTTPException(status_code=401, detail="delegation token expired")
        except jwt.InvalidAudienceError:
            raise HTTPException(status_code=403, detail="invalid audience")
        except jwt.PyJWTError as e:
            raise HTTPException(status_code=401, detail=f"invalid token: {e}")


def get_security_config_from_env(agent_name: str) -> SecurityConfig:
    """从环境变量构建安全配置（Agent 组统一入口）。"""
    return SecurityConfig(
        agent_name=agent_name,
        shared_secret=os.environ.get("AGENT_SHARED_SECRET", ""),
        jwks_url=os.environ.get("TOKEN_SERVICE_JWKS_URL", ""),
        time_window_seconds=int(os.environ.get("SECURITY_TIME_WINDOW", "30")),
        require_https=os.environ.get("REQUIRE_HTTPS", "false").lower() == "true",
    )
