"""Lost & Found Agent — MCP 适配层（Sprint 4：接通编排层）。

组员自研 REST Agent（/agent/invoke + SSE，HS256 直连通道）之上，提供
MCP streamable HTTP 端点（/mcp/），把既有业务（LLM 意图解析、规则引擎、
确认流、限流）包装成 MCP 工具 ``invoke`` —— 编排层可经标准 MCP 调用 L&F，
无需改动既有实现。

安全：与其它 MCP Agent 一致 —— 挂 ``McpSecurityMiddleware``（RS256
Delegation Token 验签 + aud=lost-found-agent + X-Timestamp 窗口）；
工具内用 ``identity_from_context``（mcp_servers.security 公共 helper，带
TokenVerifier 单例缓存）从 Authorization 解析身份，构造组员契约的
``VerifiedRequest`` 传给规则引擎。

运行（独立进程，端口 8085；REST 服务保持 8083 不变）：
    uvicorn mcp_servers.lost_found_server:app --port 8085

MCP 端点：http://<host>:8085/mcp/（编排层 LOSTFOUND_AGENT_MCP_URL 指向此处）
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator

# 允许直接运行（无需安装包）：把 agent/ 与 agent/lost_found_agent/ 加入 sys.path
# （分别用于 import mcp_servers 与 lost_found_agent 包）
_ROOT = Path(__file__).resolve().parents[1]          # agent/
_LF_ROOT = _ROOT / "lost_found_agent"               # agent/lost_found_agent/
for _p in (_ROOT, _LF_ROOT):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from fastapi import FastAPI, HTTPException

try:  # 本地联调便利：自动加载仓库根 .env；缺失时由外部注入环境变量
    from dotenv import find_dotenv, load_dotenv

    load_dotenv(find_dotenv())
except ImportError:  # pragma: no cover
    pass

try:
    from mcp.server.fastmcp import Context, FastMCP
except ImportError as _e:  # pragma: no cover - 依赖缺失/版本错误时的清晰报错
    raise ImportError(
        "无法导入 mcp.server.fastmcp：请安装 mcp 1.x（本项目锁定 1.x API）。"
        "执行：pip install \"mcp>=1.28,<2\"。"
        "注意：若之前执行过无版本限制的 pip install mcp，会装成 2.x 并移除 fastmcp 模块，需降级。"
    ) from _e

from mcp_servers.security import McpSecurityMiddleware, identity_from_context

logger = logging.getLogger(__name__)

from lost_found_agent.config import Settings, get_settings
from lost_found_agent.confirmation import ConfirmationStore
from lost_found_agent.invoke_service import LostFoundInvokeService
from lost_found_agent.llm import LlmInterpreter
from lost_found_agent.memory import MemoryClient, MemoryManager
from lost_found_agent.models import ConversationContext, InvokeRequest, InvokeResponse, TraceParent
from lost_found_agent.pretrained import PretrainedEmbeddingClient
from lost_found_agent.rate_limit import RateLimiter
from lost_found_agent.rules import RuleEngine, detect_language
from lost_found_agent.security import VerifiedRequest
from lost_found_agent.tools import CampusApiClient

AGENT_NAME = "lost-found-agent"

# 自动加载仓库根目录 .env（向上查找；不覆盖已设置的变量）
load_dotenv(find_dotenv())

@dataclass
class _McpDeps:
    """MCP 网关的已装配依赖（create_mcp_app 返回值，供宿主/测试复用）。"""

    settings: Settings
    limiter: RateLimiter
    api_client: CampusApiClient
    embedding_client: PretrainedEmbeddingClient
    llm_interpreter: LlmInterpreter | None
    confirmation_store: ConfirmationStore
    rule_engine: RuleEngine
    memory_manager: MemoryManager
    invoke_service: LostFoundInvokeService


async def _invoke_tool(
    message: str,
    conversation_context: dict | None,
    confirmed: bool,
    confirmation_id: str | None,
    trace_parent: dict | None,
    *,
    context: Context | None,
    settings: Settings,
    limiter: RateLimiter,
    invoke_service: LostFoundInvokeService,
) -> str:
    """MCP invoke 工具的核心逻辑（与 REST main.invoke 共用 LostFoundInvokeService，§7.1）。

    独立成模块级函数以便测试注入 fake（settings/limiter/invoke_service）与
    monkeypatch ``identity_from_context`` 后直接跨轮驱动，验证记忆落库（P5）。
    限流异常必须转为 failed 响应：HTTPException 在 MCP 工具层不被识别，
    直接冒泡会导致响应流中断 → 客户端连接重置（WinError 10054）。
    """
    if context is None:
        return json.dumps(
            {"response": "缺少请求上下文", "status": "failed", "request_id": ""},
            ensure_ascii=False,
        )

    request_id = str(uuid.uuid4())
    t0 = time.monotonic()
    try:
        claims = identity_from_context(context, AGENT_NAME)
    except ValueError as exc:
        logger.warning("L&F invoke auth failed: %s", exc)
        return json.dumps(
            {"response": f"鉴权失败：{exc}", "status": "failed", "request_id": request_id},
            ensure_ascii=False,
        )

    logger.info(
        "L&F invoke start: request_id=%s user_id=%s role=%s message=%.60r",
        request_id, claims.get("sub"), claims.get("role"), message,
    )

    verified = VerifiedRequest(
        user_id=str(claims.get("sub", "")),
        user_role=str(claims.get("role", "STUDENT")),
        intended_action=str(claims.get("intended_action", "invoke")),
        nonce="",  # MCP 无 X-Nonce 头；字段为必填 str，REST 契约此处为请求头值
        trace_id=claims.get("trace_id"),
        claims=claims,
    )

    try:
        conv_ctx = ConversationContext.model_validate(conversation_context or {})
    except Exception as exc:
        logger.warning("L&F invoke invalid conversation_context: %s", exc)
        return json.dumps(
            {"response": "无效的请求上下文", "status": "failed", "request_id": request_id},
            ensure_ascii=False,
        )
    session_id = conv_ctx.session_id or request_id
    try:
        limiter.check(verified.user_id, session_id)
    except HTTPException as exc:
        logger.warning(
            "L&F invoke rate-limited: user_id=%s detail=%s", verified.user_id, exc.detail,
        )
        return json.dumps(
            {"response": str(exc.detail), "status": "failed", "request_id": request_id},
            ensure_ascii=False,
        )

    try:
        payload = InvokeRequest(
            message=message,
            conversation_context=conv_ctx,
            confirmed=confirmed,
            confirmation_id=confirmation_id,
            trace_parent=TraceParent.model_validate(trace_parent or {}),
        )
    except Exception as exc:
        logger.warning("L&F invoke invalid payload: %s", exc)
        return json.dumps(
            {"response": "无效的请求参数", "status": "failed", "request_id": request_id},
            ensure_ascii=False,
        )

    response = None
    try:
        # 与 REST 共用 LostFoundInvokeService（§7.1）：含记忆加载/注入/持久化。
        # MCP 链路不注入完整 recent_messages（orchestration MemorySaver 已带，§7.6）；
        # LLM 失败重试 3 次（与历史行为一致）。
        response = await invoke_service.handle_invoke(
            payload,
            verified,
            request_id,
            emit=lambda event: None,  # 事件仅用于 REST SSE 流，MCP 同步返回不需要
            interpret_attempts=3,
            include_recent_messages=False,
        )
    except asyncio.CancelledError:
        # 客户端（编排层）超时/取消：请求中断，非内部错误。记录后保持取消语义重抛。
        logger.warning(
            "L&F invoke cancelled by client: request_id=%s elapsed=%.1fs",
            request_id, time.monotonic() - t0,
        )
        raise
    except Exception as exc:
        logger.exception("L&F invoke internal error: request_id=%s", request_id)
        response = InvokeResponse(
            response=(
                "Agent 处理请求时发生内部错误。"
                if detect_language(payload.message) == "zh"
                else "The agent encountered an internal error while processing your request."
            ),
            status="failed",
            request_id=request_id,
        )
    finally:
        # 所有退出路径（含取消）都留痕；若此日志也缺失 → 进程/事件循环级崩溃
        logger.info(
            "L&F invoke exit: request_id=%s status=%s elapsed=%.1fs",
            request_id, getattr(response, "status", "?"), time.monotonic() - t0,
        )

    # 原始输出留痕：看这条日志即可确认 L&F 到底返回了什么（response 原文 + 状态）
    logger.info(
        "L&F invoke done: request_id=%s status=%s elapsed=%.1fs response=%.300s",
        request_id, response.status, time.monotonic() - t0,
        (response.response or "")[:300],
    )
    return json.dumps(response.model_dump(), ensure_ascii=False)


def create_mcp_app(
    settings: Settings | None = None,
    api_client: CampusApiClient | None = None,
    llm_interpreter: LlmInterpreter | None = None,
    memory_client: MemoryClient | None = None,
) -> tuple[FastMCP, _McpDeps]:
    """装配 MCP 网关（与 REST ``main.create_app`` 参数一致；测试可注入 fake，P5）。

    返回 ``(mcp, deps)``：``mcp`` 已注册 ``invoke`` 工具但**未**初始化会话
    （``streamable_http_app()`` 由宿主调用，与 mcp 1.x 的 session manager 生命周期一致）；
    ``deps`` 暴露各依赖供 /health、lifespan 与测试复用。

    生产路径模块级调用一次；测试用 fake ``api_client`` / ``memory_client`` 调用后
    经 ``_invoke_tool`` 直接驱动，验证 MCP 入口跨轮记忆落库。
    """
    active_settings = settings or get_settings()
    limiter = RateLimiter(
        active_settings.agent_rate_limit_per_minute,
        active_settings.agent_rate_limit_per_session,
    )
    active_api_client = api_client or CampusApiClient(active_settings)
    embedding_client = PretrainedEmbeddingClient(active_settings)
    active_llm_interpreter: LlmInterpreter | None = None
    if active_settings.effective_mode == "llm":
        active_llm_interpreter = llm_interpreter or LlmInterpreter(active_settings)
    confirmation_store = ConfirmationStore(ttl_seconds=600)
    rule_engine = RuleEngine(
        active_api_client,
        confirmation_store,
        active_settings.lost_found_match_min_score,
        embedding_client,
    )
    # 与 REST 入口（main.create_app）共用同一套 invoke 主流程与记忆编排（§7.1）。
    memory_manager = MemoryManager(
        memory_client or MemoryClient(active_api_client),
        confirmation_store,
        llm_interpreter=active_llm_interpreter,
    )
    invoke_service = LostFoundInvokeService(
        settings=active_settings,
        rule_engine=rule_engine,
        memory_manager=memory_manager,
        llm_interpreter=active_llm_interpreter,
    )
    deps = _McpDeps(
        settings=active_settings,
        limiter=limiter,
        api_client=active_api_client,
        embedding_client=embedding_client,
        llm_interpreter=active_llm_interpreter,
        confirmation_store=confirmation_store,
        rule_engine=rule_engine,
        memory_manager=memory_manager,
        invoke_service=invoke_service,
    )

    # streamable_http_path="/"：挂载到 FastAPI 的 /mcp 后端点即 /mcp/
    mcp = FastMCP(
        f"{AGENT_NAME}-server",
        streamable_http_path="/",
        # Docker 容器间使用服务名访问，需允许非 localhost Host 头。
        host=os.environ.get("FASTMCP_HOST", "127.0.0.1"),
    )

    @mcp.tool()
    async def invoke(
        message: str,
        conversation_context: dict | None = None,
        confirmed: bool = False,
        confirmation_id: str | None = None,
        trace_parent: dict | None = None,
        context: Context | None = None,
    ) -> str:
        """处理一条用户请求（Lost & Found Agent 主入口，MCP 适配）。

        Args:
            message: 用户自然语言请求（报失 / 搜索拾获 / 查看详情 / 认领）
            conversation_context: 跨 Agent 共享上下文（可选，含 session_id / shared_data）
            confirmed: 用户是否已确认前一轮的待确认操作（HITL）
            confirmation_id: 待确认操作 ID（上一轮 needs_confirmation 返回，确认重调时传入）
            trace_parent: 分布式追踪信息（可选）

        Returns:
            JSON 字符串（status=completed / needs_confirmation / failed，
            与原 /agent/invoke 契约一致，含 confirmation_required）
        """
        return await _invoke_tool(
            message,
            conversation_context,
            confirmed,
            confirmation_id,
            trace_parent,
            context=context,
            settings=deps.settings,
            limiter=deps.limiter,
            invoke_service=deps.invoke_service,
        )

    return mcp, deps


# ──────────────────────────────────────────────────────────────────────
# 生产装配：与 REST main.create_app 一致；测试经 create_mcp_app 注入 fake（§7.1）
# ──────────────────────────────────────────────────────────────────────
mcp, _deps = create_mcp_app()

# 必须先调用 streamable_http_app() 才能访问 mcp.session_manager
# （mcp 1.x：session manager 的 task group 由 run() 初始化）
_streamable_app = mcp.streamable_http_app()

# 启动环境检查：未配置 TOKEN_SERVICE_JWKS_URL 时无法 RS256 验签，请求将全部 401
if not os.environ.get("TOKEN_SERVICE_JWKS_URL"):
    print(
        f"[{AGENT_NAME}-mcp] WARNING: 未配置 TOKEN_SERVICE_JWKS_URL（RS256 验签必需），"
        "MCP 请求将全部返回 401。请先 source 仓库根目录 .env。",
        file=sys.stderr,
    )


# ──────────────────────────────────────────────────────────────────────
# FastAPI 入口：挂载 MCP + 安全中间件
# ──────────────────────────────────────────────────────────────────────

@contextlib.asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    # mount 到 FastAPI 后子应用 lifespan 不执行，task group 永远为 None →
    # 必须由宿主应用手动 session_manager.run() 初始化（mcp 1.x 官方方式）
    async with mcp.session_manager.run():
        try:
            yield
        finally:
            await _deps.api_client.close()
            await _deps.embedding_client.close()
            if _deps.llm_interpreter:
                await _deps.llm_interpreter.close()


app = FastAPI(title=f"{AGENT_NAME} MCP Gateway", version="1.0.0", lifespan=_lifespan)
app.add_middleware(McpSecurityMiddleware, agent_name=AGENT_NAME)
app.mount("/mcp", _streamable_app)


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": f"{AGENT_NAME}-mcp",
        "mode": _deps.settings.effective_mode,
        "model_configured": bool(_deps.settings.lost_found_llm_api_key.strip()),
    }
