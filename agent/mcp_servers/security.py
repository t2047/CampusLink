"""MCP 版安全中间件 — Agent MCP Server 入站校验。

Sprint 3 安全模型（MCP 化后，对齐 docs/communication-security.md）：

- **认证**：`Authorization: Bearer <Delegation Token>`（RS256/HS256 双模式验签，
  `aud` 必须等于本 Agent 名，TTL 30s）
- **防重放**：token 短时有效 + `X-Timestamp` 时间窗口（30s）
- **传输完整性**：生产由 TLS 保证。自研 REST 时代的 body HMAC 签名与
  `jti == X-Nonce` 绑定在 MCP 层取消——MCP 请求体由 SDK 序列化为标准 JSON-RPC，
  无法按 body 动态签名，且 token 短时有效已覆盖重放风险
- 校验通过后身份写入 `request.state`（user_id / user_role），供工具实现使用。
  注意：FastMCP 工具函数无法访问 Starlette `request.state`，工具内需用
  `identity_from_context(context, agent_name)` 从当前请求头二次解析身份
  （中间件已放行，此处仅取 claims；带 TokenVerifier 单例缓存避免重复拉 JWKS）
"""

from __future__ import annotations

import logging
import os
import time
from typing import Any

import jwt
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

logger = logging.getLogger(__name__)

__all__ = [
    "McpSecurityMiddleware",
    "TokenVerifier",
    "bearer_token_from_context",
    "identity_from_context",
    "token_and_identity_from_context",
]

TOKEN_ISSUER = "token-service"
VALID_USER_ROLES = frozenset({"STUDENT", "ADMIN", "SUPER_ADMIN"})
REQUIRED_ACTION = "invoke"

# TokenVerifier 单例缓存（agent_name → verifier）：避免每个请求重复构造
# PyJWKClient（会丢失 JWKS 缓存，导致每请求重新拉取公钥）
_VERIFIERS: dict[str, TokenVerifier] = {}


def bearer_token_from_context(context: Any) -> str:
    """Extract one Bearer token from the current FastMCP request context."""
    request = getattr(getattr(context, "request_context", None), "request", None)
    headers = getattr(request, "headers", None)
    authorization = headers.get("Authorization", "") if headers else ""
    if not authorization.startswith("Bearer "):
        raise ValueError("missing bearer token")
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise ValueError("missing bearer token")
    return token


def token_and_identity_from_context(
    context: Any, agent_name: str
) -> tuple[str, dict[str, Any]]:
    """Return the original Bearer token and its verified delegation claims."""
    token = bearer_token_from_context(context)
    verifier = _VERIFIERS.get(agent_name)
    if verifier is None:
        verifier = TokenVerifier(agent_name)
        _VERIFIERS[agent_name] = verifier
    return token, verifier.verify_token(token)


def identity_from_context(context: Any, agent_name: str) -> dict[str, Any]:
    """从 FastMCP 工具上下文（Context）的当前请求 Authorization 头解析身份。

    ``McpSecurityMiddleware`` 已对请求完成 RS256 验签 + aud 匹配放行，工具能执行
    说明请求已通过校验；此处二次验签仅用于获取身份（sub / role / intended_action），
    并防止工具被绕过中间件直接调用。

    Args:
        context: FastMCP 工具注入的 ``Context``（mcp>=1.9 提供 request_context）
        agent_name: 本 Agent 名（用于 TokenVerifier 的 aud 匹配与单例缓存）

    Returns:
        Delegation Token 的 claims（含 sub / role / intended_action）

    Raises:
        ValueError: 缺少 Authorization 头或验签失败
    """
    _, claims = token_and_identity_from_context(context, agent_name)
    return claims


class TokenVerifier:
    """Delegation Token 验签（RS256，JWKS 公钥）。

    HS256 联调回退已移除（2026-08-08）：所有 Agent/Utility 必须配置
    TOKEN_SERVICE_JWKS_URL 走 RS256；未配置时直接拒绝（fail-closed）。
    """

    def __init__(self, agent_name: str):
        self.agent_name = agent_name
        self.jwks_url = os.environ.get("TOKEN_SERVICE_JWKS_URL", "")
        self.time_window = int(os.environ.get("SECURITY_TIME_WINDOW", "30"))
        self._jwks_client: jwt.PyJWKClient | None = None

    def verify_token(self, token: str) -> dict[str, Any]:
        """RS256 验签并返回 claims；任何失败抛 ValueError。"""
        if not token:
            raise ValueError("missing bearer token")
        if not self.jwks_url:
            raise ValueError("TOKEN_SERVICE_JWKS_URL not configured (RS256 required)")
        try:
            header = jwt.get_unverified_header(token)
            if header.get("alg") != "RS256":
                raise ValueError("delegation token must use RS256")
            claims = self._verify(token)
        except (jwt.PyJWTError, OSError, ValueError):
            # 后端重启会更换 RSA 密钥（每次启动随机生成），PyJWKClient 可能缓存了
            # 旧公钥（kid 不匹配）→ 强制刷新一次 JWKS 再验
            self._jwks_client = jwt.PyJWKClient(self.jwks_url)
            try:
                header = jwt.get_unverified_header(token)
                if header.get("alg") != "RS256":
                    raise ValueError("delegation token must use RS256")
                claims = self._verify(token)
            except (jwt.PyJWTError, OSError, ValueError) as second_error:
                raise ValueError("invalid delegation token") from second_error

        # PyJWT 会把 aud claim 规范化为 list（即使签发时是单个字符串）
        aud = claims.get("aud")
        if isinstance(aud, list):
            if self.agent_name not in aud:
                raise ValueError(f"token for {aud}, not '{self.agent_name}'")
        elif aud != self.agent_name:
            raise ValueError(f"token for '{aud}', not '{self.agent_name}'")

        subject = claims.get("sub")
        if not isinstance(subject, str) or not subject.isdigit() or int(subject) <= 0:
            raise ValueError("delegation token subject must be a positive numeric ID")
        if claims.get("iss") != TOKEN_ISSUER:
            raise ValueError("invalid delegation token issuer")
        if claims.get("role") not in VALID_USER_ROLES:
            raise ValueError("invalid delegation token role")
        if claims.get("intended_action") != REQUIRED_ACTION:
            raise ValueError("invalid delegation token action")
        jti = claims.get("jti")
        if not isinstance(jti, str) or not jti.strip():
            raise ValueError("delegation token jti is required")
        return claims

    def _verify(self, token: str) -> dict[str, Any]:
        """从 JWKS 拉取公钥并验签（RS256）。

        aud 值匹配由 verify_token 手动检查（aud 存在性由 require 保证）：
        decode 不传 audience 参数时 PyJWT 对含 aud 的 token 默认抛
        "Invalid audience"，会盖过更明确的手动检查信息。
        """
        if self._jwks_client is None:
            self._jwks_client = jwt.PyJWKClient(self.jwks_url)
        signing_key = self._jwks_client.get_signing_key_from_jwt(token)
        return jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            issuer=TOKEN_ISSUER,
            options={
                "require": [
                    "sub",
                    "aud",
                    "exp",
                    "iat",
                    "iss",
                    "role",
                    "intended_action",
                    "jti",
                ],
                "verify_aud": False,
            },
        )


class McpSecurityMiddleware(BaseHTTPMiddleware):
    """FastAPI 中间件：校验所有 MCP 请求（含 initialize 握手）。"""

    def __init__(self, app, agent_name: str):
        super().__init__(app)
        self.verifier = TokenVerifier(agent_name)

    async def dispatch(self, request: Request, call_next):
        # 健康检查放行（K8s/Docker 探针）
        if request.url.path == "/health":
            return await call_next(request)
        try:
            auth = request.headers.get("Authorization", "")
            if not auth.startswith("Bearer "):
                raise ValueError("missing bearer token")
            claims = self.verifier.verify_token(auth.removeprefix("Bearer ").strip())

            # 可选时间窗口防重放（token 本身 30s TTL 已兜底）
            ts_str = request.headers.get("X-Timestamp")
            if ts_str:
                try:
                    ts = int(ts_str)
                except ValueError:
                    raise ValueError("invalid timestamp")
                if abs(int(time.time()) - ts) > self.verifier.time_window:
                    raise ValueError("timestamp window exceeded")

            request.state.user_id = claims.get("sub")
            request.state.user_role = claims.get("role")
            request.state.intended_action = claims.get("intended_action", "invoke")
        except ValueError as e:
            logger.warning(
                "McpSecurityMiddleware rejected %s %s: %s",
                request.method,
                request.url.path,
                e,
            )
            return JSONResponse(status_code=401, content={"detail": str(e)})

        return await call_next(request)
