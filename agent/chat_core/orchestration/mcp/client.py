"""MCP 客户端与服务注册表 — Chat Core 编排层。

- ServiceRegistry: 从 services.yaml 加载所有服务配置
- AgentClient:     调用 Domain Agent 的 POST /agent/invoke（携带安全 Headers）
                   + 调用 Utility Tool 的 POST /tools/call
                   + Sprint 1 本地签发 HS256 Delegation Token（与 Mock Agent 联调用）

安全说明（对齐通信安全说明文档）：
- Sprint 1-2：编排层用 AGENT_SHARED_SECRET 本地签发 HS256 Delegation Token
  （仅用于本地联调；Agent 端 HS256 验签共享同一密钥）
- Sprint 3+：改为调用 Token Service POST /internal/token/exchange 获取 RS256 Token
  （Agent 端从 JWKS 端点验签），本类保留同签名方法，切换成本低
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

import httpx

from .registry import DEFAULT_CONFIG_PATH, AgentConfig, ServiceRegistry


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
        delegation_token: str,
        conversation_context: Optional[dict] = None,
        trace_id: Optional[str] = None,
        confirmed: bool = False,
    ) -> dict[str, Any]:
        """调用 Domain Agent 的 POST /agent/invoke。

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

        headers = self._build_secure_headers(body, delegation_token, user_id, user_role, trace_id)

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

    async def invoke_utility(self, tool_name: str, params: dict[str, Any], delegation_token: str) -> dict[str, Any]:
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

        headers = self._build_secure_headers(body, delegation_token, "", "", None)

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
        user_jwt: str,
        target_agent: str,
        intended_action: str = "invoke",
    ) -> dict[str, Any]:
        """从 Token Service 获取 Delegation Token（Sprint 3 起启用）。"""
        if not self.registry.token_service_url:
            return {"error": "token service not configured", "status": "failed"}
        response = await self._http.post(
            f"{self.registry.token_service_url}/internal/token/exchange",
            json={
                "user_jwt": user_jwt,
                "target_agent": target_agent,
                "intended_action": intended_action,
            },
            timeout=3000 / 1000.0,
        )
        response.raise_for_status()
        return response.json()

    # ──────────────────────────────────────────────────────────────────
    # Sprint 1 本地签发 HS256 Delegation Token（与 Mock Agent 联调）
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

        仅用于 Sprint 1-2 本地联调（Agent 端 HS256 模式验签）。
        Sprint 3+ 切换到 Token Service RS256 后此方法废弃。
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
    ) -> dict[str, str]:
        nonce = str(uuid.uuid4())
        timestamp = int(time.time())
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
