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

from lost_found_agent.config import get_settings
from lost_found_agent.confirmation import ConfirmationStore
from lost_found_agent.llm import LlmInterpreter, LlmUnavailable, interpret_with_retry
from lost_found_agent.models import ConversationContext, InvokeRequest, InvokeResponse, TraceParent
from lost_found_agent.pretrained import PretrainedEmbeddingClient
from lost_found_agent.rate_limit import RateLimiter
from lost_found_agent.rules import RuleEngine
from lost_found_agent.security import VerifiedRequest
from lost_found_agent.tools import CampusApiClient

AGENT_NAME = "lost-found-agent"

# 自动加载仓库根目录 .env（向上查找；不覆盖已设置的变量）
load_dotenv(find_dotenv())

# ──────────────────────────────────────────────────────────────────────
# 依赖装配（与 main.create_app 一致的参数；MCP 网关独立进程，事件存储省略
# —— MCP 为同步调用，无 SSE 消费方）
# ──────────────────────────────────────────────────────────────────────
_settings = get_settings()
_limiter = RateLimiter(
    _settings.agent_rate_limit_per_minute,
    _settings.agent_rate_limit_per_session,
)
_api_client = CampusApiClient(_settings)
_embedding_client = PretrainedEmbeddingClient(_settings)
_llm_interpreter: LlmInterpreter | None = None
if _settings.effective_mode == "llm":
    _llm_interpreter = LlmInterpreter(_settings)
_rule_engine = RuleEngine(
    _api_client,
    ConfirmationStore(ttl_seconds=600),
    _settings.lost_found_match_min_score,
    _embedding_client,
)

# 启动环境检查：未配置 TOKEN_SERVICE_JWKS_URL 时无法 RS256 验签，请求将全部 401
if not os.environ.get("TOKEN_SERVICE_JWKS_URL"):
    print(
        f"[{AGENT_NAME}-mcp] WARNING: 未配置 TOKEN_SERVICE_JWKS_URL（RS256 验签必需），"
        "MCP 请求将全部返回 401。请先 source 仓库根目录 .env。",
        file=sys.stderr,
    )

# streamable_http_path="/"：挂载到 FastAPI 的 /mcp 后端点即 /mcp/
mcp = FastMCP(
    f"{AGENT_NAME}-server",
    streamable_http_path="/",
    # Docker 容器间使用服务名访问，需允许非 localhost Host 头。
    host=os.environ.get("FASTMCP_HOST", "127.0.0.1"),
)

# 必须先调用 streamable_http_app() 才能访问 mcp.session_manager
# （mcp 1.x：session manager 的 task group 由 run() 初始化）
_streamable_app = mcp.streamable_http_app()


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
    # 限流异常必须转为 failed 响应：HTTPException 在 MCP 工具层不被识别，
    # 直接冒泡会导致响应流中断 → 客户端连接重置（WinError 10054）
    try:
        _limiter.check(verified.user_id, session_id)
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
        interpretation = None
        if _llm_interpreter and not (payload.confirmed or payload.confirmation_id):
            try:
                interpretation = await interpret_with_retry(
                    _llm_interpreter,
                    payload.message,
                    payload.conversation_context.shared_data,
                )
            except LlmUnavailable as exc:
                if _settings.llm_fail_closed:
                    # fail-closed（默认）：LLM 不可用/输出不可信 → 显式失败，不降级规则
                    logger.warning(
                        "L&F invoke LLM fail-closed: request_id=%s err=%s",
                        request_id, exc,
                    )
                    return json.dumps(
                        {
                            "response": "智能识别服务暂时不可用，请稍后重试。",
                            "status": "failed",
                            "error": f"llm_fail_closed: {exc}",
                            "request_id": request_id,
                        },
                        ensure_ascii=False,
                    )
                # 旧行为（降级规则引擎）：仅当 llm_fail_closed=false 时生效
                pass

        response = await _rule_engine.handle(
            payload,
            verified,
            request_id,
            lambda event: None,  # 事件仅用于 REST SSE 流，MCP 同步返回不需要
            interpreted_intent=interpretation.intent if interpretation else None,
            interpreted_fields=(
                interpretation.fields.model_dump(exclude_none=True)
                if interpretation
                else None
            ),
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
            response="Agent 处理请求时发生内部错误。",
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
            await _api_client.close()
            await _embedding_client.close()
            if _llm_interpreter:
                await _llm_interpreter.close()


app = FastAPI(title=f"{AGENT_NAME} MCP Gateway", version="1.0.0", lifespan=_lifespan)
app.add_middleware(McpSecurityMiddleware, agent_name=AGENT_NAME)
app.mount("/mcp", _streamable_app)


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": f"{AGENT_NAME}-mcp",
        "mode": _settings.effective_mode,
        "model_configured": bool(_settings.lost_found_llm_api_key.strip()),
    }
