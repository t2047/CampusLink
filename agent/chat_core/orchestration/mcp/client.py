"""MCP 客户端与服务注册表 — Chat Core 编排层。

- ServiceRegistry: 从 services.yaml 加载所有服务配置
- AgentClient:     调用 Domain Agent 的 POST /agent/invoke（携带安全 Headers）
                   + 调用 Utility Tool 的 POST /tools/call
                   + 兑换 RS256 Delegation Token（Token Service，当前内嵌于 Chat Backend）

安全说明（对齐通信安全说明文档）：
- 编排层 → Agent：从 Token Service 兑换 RS256 Delegation Token
  （POST {token_service_url}/internal/token/exchange），Agent 端从 JWKS 端点验签
- Token Service 不可用时的回退：用 AGENT_SHARED_SECRET 本地签发 HS256 Delegation Token
  （仅用于本地联调；Agent 端 HS256 模式验签共享同一密钥）
- Sprint 3+：Token Service 独立部署，仅切换 TOKEN_SERVICE_URL，接口形态不变
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

import httpx

from .registry import DEFAULT_CONFIG_PATH, AgentConfig, ServiceRegistry

logger = logging.getLogger(__name__)


@dataclass
class AgentClient:
    """编排层调用 Agent / Utility Tool 的 HTTP 客户端。"""

    registry: ServiceRegistry
    _http: httpx.AsyncClient = field(default_factory=lambda: httpx.AsyncClient(timeout=httpx.Timeout(30.0)))

    @classmethod
    def from_yaml(cls, path: str = DEFAULT_CONFIG_PATH) -> "AgentClient":
        return cls(registry=ServiceRegistry.from_yaml(path))

    async def invoke_agent(
        self,
        agent_name: str,
        message: str,
        user_id: str,
        user_role: str,
        delegation_token: Optional[str] = None,
        conversation_context: Optional[dict] = None,
        trace_id: Optional[str] = None,
        confirmed: bool = False,
    ) -> dict[str, Any]:
        """调用 Domain Agent 的 POST /agent/invoke。

        delegation_token 未提供时，内部先兑换 RS256 Delegation Token
        （Token Service 不可用则回退本地 HS256，仅联调）。
        任何失败（超时/网络/HTTP 错误）都返回降级结构，不向上抛异常。
        """
        agent = self.registry.get_agent(agent_name)
        if not agent:
            return {"response": f"Agent {agent_name} 未配置", "status": "failed", "error": "not_found"}

        body: dict[str, Any] = {
            "message": message,
            "conversation_context": conversation_context or {},
            "confirmed": confirmed,
            "trace_parent": {
                "trace_id": trace_id or str(uuid.uuid4()),
                "parent_span_id": str(uuid.uuid4()),
            },
        }

        # Nonce 先于 token 生成：作为 jti 绑定进 Delegation Token（Agent 端校验 claims.jti == X-Nonce）
        nonce = str(uuid.uuid4())
        timestamp = int(time.time())
        token = delegation_token or await self._obtain_delegation_token(
            user_id, user_role, agent_name, nonce
        )
        if not token:
            return {
                "response": "安全令牌获取失败，请稍后重试",
                "status": "failed",
                "error": "token_unavailable",
            }
        headers = self._build_secure_headers(
            body, token, user_id, user_role, trace_id, nonce=nonce, timestamp=timestamp
        )

        try:
            response = await self._http.post(
                f"{agent.url}/agent/invoke",
                json=body,
                headers=headers,
                timeout=agent.timeout_ms / 1000.0,
            )
            response.raise_for_status()
            return response.json()
        except httpx.TimeoutException:
            return {"response": f"「{agent_name}」响应超时，请稍后重试", "status": "failed", "error": "timeout"}
        except httpx.HTTPStatusError as e:
            if e.response.status_code in (401, 403):
                return {"response": "安全验证失败，请重新登录", "status": "failed", "error": "security"}
            return {
                "response": f"「{agent_name}」返回错误 {e.response.status_code}",
                "status": "failed",
                "error": "http_error",
            }
        except httpx.HTTPError as e:
            return {"response": f"调用「{agent_name}」失败: {e}", "status": "failed", "error": "network"}

    async def invoke_utility(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str = "",
        user_role: str = "",
        delegation_token: Optional[str] = None,
    ) -> dict[str, Any]:
        """调用 Utility MCP Server 的 POST /tools/call（JSON-RPC 2.0）。"""
        utility_url = self.registry.utility_url
        if not utility_url:
            return {"error": "utility tools not configured", "status": "failed"}

        body = {
            "jsonrpc": "2.0",
            "id": str(uuid.uuid4()),
            "method": "tools/call",
            "params": {"name": tool_name, "arguments": params},
        }

        nonce = str(uuid.uuid4())
        timestamp = int(time.time())
        token = delegation_token or await self._obtain_delegation_token(
            user_id, user_role, "utility-tools", nonce
        )
        if not token:
            return {"error": "安全令牌获取失败", "status": "failed", "error": "token_unavailable"}
        headers = self._build_secure_headers(
            body, token, user_id, user_role, None, nonce=nonce, timestamp=timestamp
        )

        try:
            response = await self._http.post(
                f"{utility_url}/tools/call",
                json=body,
                headers=headers,
                timeout=5000 / 1000.0,
            )
            response.raise_for_status()
            data = response.json()
            if "error" in data:
                return {"error": data["error"].get("message", "tool error"), "status": "failed"}
            return data.get("result", {})
        except (httpx.TimeoutException, httpx.HTTPError) as e:
            return {"error": str(e), "status": "failed"}

    async def get_delegation_token(
        self,
        user_id: str,
        role: str,
        target_agent: str,
        intended_action: str = "invoke",
        jti: Optional[str] = None,
    ) -> Optional[str]:
        """从 Token Service 兑换 RS256 Delegation Token（当前内嵌于 Chat Backend）。

        Args:
            user_id: 用户 ID（来自 Chat Backend 转发到编排层的可信身份）
            role: 用户角色
            target_agent: 目标 Agent（aud）
            intended_action: 预期操作
            jti: 即将用于调用 Agent 的 X-Nonce，绑定进 token 的 jti（防重放一致性）

        Returns:
            RS256 JWT 字符串；未配置 / 网络 / 非 2xx 失败时返回 None。
        """
        if not self.registry.token_service_url:
            logger.warning("token_service 未配置，无法兑换 RS256 Delegation Token")
            return None

        body: dict[str, Any] = {
            "userId": user_id,
            "role": role,
            "targetAgent": target_agent,
            "intendedAction": intended_action,
        }
        if jti:
            body["jti"] = jti
        body_str = json.dumps(body, ensure_ascii=False, separators=(",", ":"))

        # 本请求自身的防重放 Header（与编排层入站安全中间件同款 HMAC）
        nonce = str(uuid.uuid4())
        timestamp = int(time.time())
        headers = {
            "Content-Type": "application/json",
            "X-Nonce": nonce,
            "X-Timestamp": str(timestamp),
            "X-Signature": self._sign(body_str, nonce, timestamp),
        }

        try:
            response = await self._http.post(
                f"{self.registry.token_service_url}/internal/token/exchange",
                json=body,
                headers=headers,
                timeout=3000 / 1000.0,
            )
            response.raise_for_status()
            token = response.json().get("token")
            if not token:
                logger.error("Token Service 响应缺少 token 字段: %s", response.json())
                return None
            return token
        except Exception as e:
            logger.error("Token Service 兑换失败: %s", e)
            return None

    async def _obtain_delegation_token(
        self, user_id: str, role: str, target_agent: str, nonce: str
    ) -> Optional[str]:
        """RS256（Token Service）优先；失败时按 allow_hs256_fallback 决定回退或拒绝。

        nonce 作为 jti 传入，保证两条路径下 claims.jti == 调用 Agent 时的 X-Nonce。
        返回 None 表示无法获得可用 token（调用方应降级，不调用 Agent）。
        """
        token = await self.get_delegation_token(
            user_id=user_id, role=role, target_agent=target_agent, jti=nonce
        )
        if token:
            return token
        if not self.registry.allow_hs256_fallback:
            logger.error(
                "Token Service 不可用且 allow_hs256_fallback=false（fail-closed），拒绝调用: target=%s",
                target_agent,
            )
            return None
        logger.warning(
            "Delegation Token 回退本地 HS256 签发（仅限联调，生产应设 ALLOW_HS256_FALLBACK=false）: target=%s",
            target_agent,
        )
        return self.issue_local_delegation_token(
            user_id=user_id, role=role, target_agent=target_agent, nonce=nonce
        )

    # ──────────────────────────────────────────────────────────────────
    # 本地 HS256 签发（Token Service 不可用时的联调回退，受 ALLOW_HS256_FALLBACK 控制）
    # ──────────────────────────────────────────────────────────────────

    def issue_local_delegation_token(
        self,
        user_id: str,
        role: str,
        target_agent: str,
        nonce: Optional[str] = None,
        ttl_seconds: int = 30,
    ) -> str:
        """用 AGENT_SHARED_SECRET 本地签发 HS256 Delegation Token。

        仅作为 Token Service 不可用时的联调回退（Agent 端 HS256 模式验签）。
        nonce 作为 jti（默认随机），保证 claims.jti == 调用 Agent 时的 X-Nonce。
        Sprint 3+ Token Service 独立部署后应移除本回退。
        """
        try:
            import jwt as pyjwt
        except ImportError:  # pragma: no cover
            raise RuntimeError("PyJWT not installed")

        now = int(time.time())
        payload = {
            "sub": user_id,
            "role": role,
            "aud": target_agent,
            "iss": "chat-backend",
            "iat": now,
            "exp": now + ttl_seconds,
            "jti": nonce or str(uuid.uuid4()),
            "intended_action": "invoke",
            "delegated_by": "orchestration",
        }
        return pyjwt.encode(payload, self.registry.shared_secret, algorithm="HS256")

    # ──────────────────────────────────────────────────────────────────
    # 安全 Headers 构建
    # ──────────────────────────────────────────────────────────────────

    def _build_secure_headers(
        self,
        body: dict[str, Any],
        delegation_token: str,
        user_id: str,
        user_role: str,
        trace_id: Optional[str],
        nonce: Optional[str] = None,
        timestamp: Optional[int] = None,
    ) -> dict[str, str]:
        """构建 Agent 请求安全 Headers。

        nonce/timestamp 可由调用方注入（须与签发 Delegation Token 时传入的 jti 一致），
        否则内部生成。
        """
        nonce = nonce or str(uuid.uuid4())
        timestamp = timestamp if timestamp is not None else int(time.time())
        body_str = json.dumps(body, ensure_ascii=False, separators=(",", ":"))

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {delegation_token}",
            "X-Nonce": nonce,
            "X-Timestamp": str(timestamp),
            "X-Signature": self._sign(body_str, nonce, timestamp),
            "X-Trace-Id": trace_id or "",
        }
        if user_id:
            headers["X-User-Id"] = user_id
        if user_role:
            headers["X-User-Role"] = user_role
        return headers

    def _sign(self, body: str, nonce: str, timestamp: int) -> str:
        """HMAC-SHA256 请求签名（防篡改）。"""
        message = f"{body}:{nonce}:{timestamp}"
        secret = (self.registry.shared_secret or "").encode()
        return hmac.new(secret, message.encode(), hashlib.sha256).hexdigest()

    async def close(self) -> None:
        await self._http.aclose()
