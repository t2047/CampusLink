"""MCP 客户端 — Chat Core 编排层。

- ServiceRegistry: 从 services.yaml 加载所有服务配置（含各 Agent 的 MCP 端点）
- AgentClient:     通过 MCP streamable HTTP 调用 Domain Agent（invoke 工具）
                   + Utility Tool Server（5 个工具）
                   + 兑换 RS256 Delegation Token（Token Service，当前内嵌于 Chat Backend）

安全说明（Sprint 3，对齐 docs/communication-security.md）：
- 编排层 → Agent：MCP 请求带 Authorization: Bearer <Delegation Token>
  （RS256，从 Token Service 兑换；兑换失败 fail-closed 拒绝），Agent 端 McpSecurityMiddleware 验签 + aud 匹配
- 自研 REST 时代的 body HMAC 签名与 jti==X-Nonce 绑定已取消：
  MCP 请求体由 SDK 序列化为标准 JSON-RPC，完整性交给生产 TLS；
  防重放由 token 30s TTL + X-Timestamp 窗口承担
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

import httpx

try:
    from mcp import ClientSession
    from mcp.client.streamable_http import streamable_http_client
except ImportError as _e:  # pragma: no cover - 依赖缺失时的清晰报错
    raise ImportError(
        "No module named 'mcp'"
        "Try pip install \"mcp>=1.28,<2\""
    ) from _e

from .registry import DEFAULT_CONFIG_PATH, ServiceRegistry

logger = logging.getLogger(__name__)

# MCP 调用超时（秒）
_MCP_TIMEOUT = httpx.Timeout(30.0, connect=5.0)


def _root_cause(e: BaseException) -> str:
    """解包 TaskGroup/ExceptionGroup 异常，返回最内层原因的可读文本。

    mcp SDK 内部用 asyncio.TaskGroup 管理后台任务，子任务异常在上下文退出时
    会包装为 ExceptionGroup（"unhandled errors in a TaskGroup"），掩盖真实原因。
    """
    while isinstance(e, BaseExceptionGroup) and e.exceptions:
        e = e.exceptions[0]
    return str(e) or e.__class__.__name__


def _root_exc(e: BaseException) -> BaseException:
    """解包异常组，返回最内层的异常对象（用于类型判断）。"""
    while isinstance(e, BaseExceptionGroup) and e.exceptions:
        e = e.exceptions[0]
    return e


def _describe_mcp_failure(e: BaseException, service: str, url: str) -> str:
    """把 MCP 调用异常转成明确的英文报错。

    - 传输层失败（连接拒绝/超时等，通常意味着 MCP 服务未启动）→ 明确指出服务与地址
    - 其他异常 → 保留原始原因文本
    """
    root = _root_exc(e)
    if isinstance(root, httpx.TransportError):
        return (
            f"MCP service '{service}' is unreachable at {url}: {root}. "
            "Please ensure the service is running."
        )
    return _root_cause(e) or root.__class__.__name__


@dataclass
class AgentClient:
    """编排层调用 Agent / Utility Tool 的 MCP 客户端。"""

    registry: ServiceRegistry
    _http: httpx.AsyncClient = field(default_factory=lambda: httpx.AsyncClient(timeout=_MCP_TIMEOUT))

    @classmethod
    def from_yaml(cls, path: str = DEFAULT_CONFIG_PATH) -> "AgentClient":
        return cls(registry=ServiceRegistry.from_yaml(path))

    # ──────────────────────────────────────────────────────────────────
    # 对外调用入口
    # ──────────────────────────────────────────────────────────────────

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
        confirmation_id: Optional[str] = None,
    ) -> dict[str, Any]:
        """通过 MCP 调用 Domain Agent 的 ``invoke`` 工具。

        任何失败（超时/网络/工具错误）都返回降级结构，不向上抛异常。
        """
        agent = self.registry.get_agent(agent_name)
        if not agent:
            return {"response": f"Agent {agent_name} 未配置", "status": "failed", "error": "not_found"}
        if not agent.mcp_url:
            return {"response": f"Agent {agent_name} 未配置 MCP 端点", "status": "failed", "error": "no_mcp_url"}

        token = delegation_token or await self._obtain_delegation_token(
            user_id, user_role, agent_name
        )
        if not token:
            return {
                "response": "安全令牌获取失败，请稍后重试",
                "status": "failed",
                "error": "token_unavailable",
            }

        arguments = {
            "message": message,
            "conversation_context": conversation_context or {},
            "confirmed": confirmed,
            "confirmation_id": confirmation_id,
            "trace_parent": {
                "trace_id": trace_id or str(uuid.uuid4()),
                "parent_span_id": str(uuid.uuid4()),
            },
        }

        try:
            raw = await self._call_mcp_tool(agent.mcp_url, "invoke", arguments, token)
            return self._normalize_result(raw, agent_name)
        except Exception as e:
            detail = _describe_mcp_failure(e, agent_name, agent.mcp_url or "")
            logger.error("invoke_agent %s failed: %s", agent_name, detail, exc_info=True)
            # error 字段：英文技术详情（日志/排查）；response 字段：用户可见友好文案
            return {
                "response": f"「{agent_name}」服务暂时不可用，请稍后重试。",
                "status": "failed",
                "error": detail,
            }

    async def invoke_utility(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str = "",
        user_role: str = "",
        delegation_token: Optional[str] = None,
    ) -> dict[str, Any]:
        """通过 MCP 调用 Utility Tool Server 的对应工具。"""
        utility_mcp = self.registry.utility_mcp_url
        if not utility_mcp:
            return {"error": "utility mcp not configured", "status": "failed"}

        token = delegation_token or await self._obtain_delegation_token(
            user_id, user_role, "utility-tools"
        )
        if not token:
            return {"status": "failed", "error": "token_unavailable"}

        try:
            raw = await self._call_mcp_tool(utility_mcp, tool_name, params, token)
            if isinstance(raw, dict) and raw.get("status") == "failed":
                return raw
            return raw
        except Exception as e:
            detail = _describe_mcp_failure(e, "utility-tools", utility_mcp)
            logger.error("invoke_utility %s failed: %s", tool_name, detail, exc_info=True)
            return {"error": detail, "status": "failed"}

    # ──────────────────────────────────────────────────────────────────
    # MCP 传输
    # ──────────────────────────────────────────────────────────────────

    async def _call_mcp_tool(
        self,
        mcp_url: str,
        tool_name: str,
        arguments: dict[str, Any],
        token: str,
    ) -> dict[str, Any]:
        """建立 MCP session 并调用工具（每次调用独立连接，简单可靠）。"""
        headers = {
            "Authorization": f"Bearer {token}",
            "X-Timestamp": str(int(time.time())),
        }
        async with httpx.AsyncClient(headers=headers, timeout=_MCP_TIMEOUT) as http:
            async with streamable_http_client(mcp_url, http_client=http) as (read, write, _):
                async with ClientSession(read, write) as session:
                    await session.initialize()
                    result = await session.call_tool(tool_name, arguments)
        return self._parse_mcp_result(result)

    def _parse_mcp_result(self, result: Any) -> dict[str, Any]:
        """从 CallToolResult 提取结构化结果。

        主路径：Server 端工具返回 JSON 字符串 → text content → json.loads。
        回退：structured_content（SDK v1.30+ 为 list[StructuredContent]），
        仅提取其中形如业务 dict（含 response/status 字段）的项。
        """
        if getattr(result, "isError", False):
            return {"error": "mcp tool error", "status": "failed"}

        # 1) text content（Server 端约定返回 JSON 字符串）
        texts = [
            c.text for c in (result.content or []) if getattr(c, "type", "") == "text"
        ]
        text = "\n".join(texts).strip()
        if text:
            try:
                parsed = json.loads(text)
                if isinstance(parsed, dict):
                    return parsed
                return {"response": str(parsed), "status": "completed"}
            except Exception:
                return {"response": text, "status": "completed"}

        # 2) 回退：structured_content 中直接含业务字段的项
        structured = getattr(result, "structured_content", None)
        if structured:
            for item in structured:
                if isinstance(item, dict) and (
                    "response" in item or "status" in item
                ):
                    return item
                value = getattr(item, "value", None)
                if isinstance(value, dict) and (
                    "response" in value or "status" in value
                ):
                    return value

        return {"response": "", "status": "completed"}

    def _normalize_result(self, raw: dict[str, Any], agent_name: str) -> dict[str, Any]:
        """归一化 Agent 调用结果（对齐原 REST 契约的字段）。"""
        return {
            "response": raw.get("response", ""),
            "status": raw.get("status", "completed"),
            "confirmation_required": raw.get("confirmation_required"),
            "shared_context": raw.get("shared_context", {}),
            "actions_taken": raw.get("actions_taken", []),
            "match_results": raw.get("match_results", []),
            "request_id": raw.get("request_id"),
            "error": raw.get("error"),
        }

    # ──────────────────────────────────────────────────────────────────
    # Delegation Token 获取（保留自 Sprint 2）
    # ──────────────────────────────────────────────────────────────────

    async def get_delegation_token(
        self,
        user_id: str,
        role: str,
        target_agent: str,
        intended_action: str = "invoke",
        jti: Optional[str] = None,
    ) -> Optional[str]:
        """从 Token Service 兑换 RS256 Delegation Token（当前内嵌于 Chat Backend）。

        MCP 层不要求 jti 绑定 X-Nonce（jti 由签发方随机生成即可）。
        失败（未配置 / 网络 / 非 2xx）返回 None。
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

        # Token Service 兑换请求自身的防重放 Header（HMAC，与后端 TokenExchangeController 一致）
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
        self, user_id: str, role: str, target_agent: str, jti: Optional[str] = None
    ) -> Optional[str]:
        """从 Token Service 兑换 RS256 Delegation Token（fail-closed）。

        兑换失败（未配置 / 网络 / 非 2xx）返回 None → 调用方拒绝调用，
        不降级到本地签发（HS256 联调回退已移除，2026-08-08）。
        """
        token = await self.get_delegation_token(
            user_id=user_id, role=role, target_agent=target_agent, jti=jti
        )
        if token:
            return token
        logger.error(
            "Token Service 不可用（fail-closed），拒绝调用: target=%s。"
            "请确保 Chat Backend 已启动且 TOKEN_SERVICE_URL 配置正确。",
            target_agent,
        )
        return None

    def _sign(self, body: str, nonce: str, timestamp: int) -> str:
        """HMAC-SHA256 请求签名（仅用于 Token Service 兑换请求）。"""
        import hashlib
        import hmac

        message = f"{body}:{nonce}:{timestamp}"
        secret = (self.registry.shared_secret or "").encode()
        return hmac.new(secret, message.encode(), hashlib.sha256).hexdigest()

    async def close(self) -> None:
        await self._http.aclose()
