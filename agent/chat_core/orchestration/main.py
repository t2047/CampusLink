"""Chat Core 编排层入口 — FastAPI 应用。

端点：
- GET  /health                       健康检查
- POST /chat/stream                  接收 Chat Backend 请求，跑 LangGraph，返回 SSE 事件流

安全：
- /chat/stream 由编排层入站安全中间件校验（Chat Backend 共享密钥 HMAC 签名 + Nonce 防重放）
- 编排层 → Agent：RS256 Delegation Token 从 Token Service 兑换（当前内嵌于 Chat
  Backend 的 POST /internal/token/exchange；独立部署 Sprint 3+，仅切换 TOKEN_SERVICE_URL）
- Token Service 不可用时 fail-closed 拒绝调用（HS256 本地回退已移除，2026-08-08）

流式（修复"整段一起出 + 慢"）：
- 之前：graph.ainvoke 等整个图跑完 → 一次性构造全部 SSE → 慢且无打字机效果
- 现在：graph.astream(stream_mode=["messages", "updates"]) 边跑边发
  - stream_mode="messages"：chat_responder 的 LLM token 级增量 → 实时 token 事件（打字机）
  - stream_mode="updates"：节点完成 → 结构化事件（intent_detected / agent_start / ...）
  - token 过滤：只透传 chat_responder 的流式 token，避免意图分类 LLM 的 token 泄漏到前端

空回复兜底：
- chat 路径：messages 模式逐字发出失败时，updates 模式把完整回复作为单条 token 兜底
- 图异常 / 无节点处理 / 无任何输出：直接用 LLM 回复用户（_direct_llm_reply），
  保证"永远有回复"；LLM 兜底也失败才发 error 事件

多轮对话（启用后）：
- 同一会话复用 thread_id（前端透传 session_id，经后端 → 编排层 ChatRequest.sessionId）
- MemorySaver checkpoint 累积 messages，实现跨消息上下文
- 防护：若该 thread 上次运行停在中断（HITL interrupt），恢复需要 Command(resume=...)，
  直接复用会挂起（"一直回复中"）；_thread_config 在请求前检查
  graph.get_state().next，非空则换新 thread（放弃旧上下文，但不卡住）
- HITL 确认恢复（Command(resume=...)）为 Sprint 3 待办
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import uuid
from collections.abc import AsyncGenerator
from typing import Any

from dotenv import find_dotenv, load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage
from langgraph.types import Command
from pydantic import BaseModel, Field

# 自动加载仓库根目录 .env（向上查找；不覆盖已设置的变量）
load_dotenv(find_dotenv())

from .graph.graph import build_graph
from .llm import chat_llm
from .mcp.registry import ServiceRegistry
from .security.middleware import OrchestrationInboundSecurity
from .streaming.sse_handler import SSEEvent, structural_events_from_update

logger = logging.getLogger(__name__)

app = FastAPI(
    title="CampusLink Chat Orchestration",
    version="0.1.0",
    description="LangGraph 编排层 — 意图路由 + Agent 调度 + 结果聚合",
)

# ── 全局单例（启动时惰性初始化）──
_graph = None
_registry = None
_inbound_security = None


def _get_registry() -> ServiceRegistry:
    global _registry
    if _registry is None:
        _registry = ServiceRegistry.from_yaml()
    return _registry


def _get_inbound_security() -> OrchestrationInboundSecurity:
    global _inbound_security
    if _inbound_security is None:
        _inbound_security = OrchestrationInboundSecurity(_get_registry())
    return _inbound_security


def _get_graph():
    global _graph
    if _graph is None:
        _graph = build_graph()
    return _graph


def _sanitize_thread_id(raw: str) -> str:
    """校验并清洗会话 ID（thread_id 会作为 MemorySaver checkpoint key）。"""
    if not raw:
        return ""
    return re.sub(r"[^A-Za-z0-9_-]", "", raw)[:128]


def _thread_config(graph, session_id: str) -> dict:
    """构造 LangGraph 配置：多轮上下文按 session_id 复用 thread_id。

    若该 thread 上次运行停在中断（HITL human_approval 的 interrupt），直接恢复会
    挂起（此前"第二条消息一直回复中"的根因）；此时放弃旧 checkpoint 换新 thread
    （本次无历史上下文，但不会卡住）。正常结束的 thread 的 next 为空 → 正常复用。
    """
    base = _sanitize_thread_id(session_id) or str(uuid.uuid4())
    config = {"configurable": {"thread_id": base}}
    try:
        snapshot = graph.get_state(config)
        if snapshot and snapshot.next:
            logger.warning(
                "thread %s 上次运行未结束（停在 %s），换新 thread 避免恢复挂起",
                base,
                snapshot.next,
            )
            config["configurable"]["thread_id"] = f"{base}:{uuid.uuid4()}"
    except Exception:
        pass  # 无 checkpoint / 单测假图等场景，直接用原 thread
    return config


def _build_initial_state(payload: ChatRequest, trace_id: str, session_id: str) -> dict[str, Any]:
    """构造每轮图的初始状态（LangGraph 输入与 checkpoint 合并时输入优先）。

    注意：pending_info 刻意不在此处设置——澄清循环状态必须来自 checkpoint 跨轮
    保留。若在这里重置为 None，会覆盖上一轮 needs_more_info 后 agent_invoker
    写入的澄清状态，导致补充消息（如 "today"、"15-8-2026"）被 LLM 意图分类
    误判为闲聊，出现"请提供日期 → 补充日期 → 答非所问"（2026-08-15 修复）。
    消费/清空由 agent_invoker（每轮默认 None 再按需覆盖）与 intent_router 的
    abandon 分支负责。
    """
    return {
        "messages": [HumanMessage(content=payload.message)],
        "intent_type": None,
        "targets": [],
        "agent_plan": [],
        "utility_plan": [],
        "utility_results": {},
        "utility_response": None,
        "current_agent_index": 0,
        "user_id": payload.userId,
        "user_role": payload.role,
        "trace_id": trace_id,
        "session_id": session_id,
        "conversation_context": payload.conversationContext,
        "requires_approval": False,
        "approval_context": None,
        "approval_agent": None,
        "pending_confirmation": None,
        "error": None,
        "failed_agents": [],
        "service_failures": [],  # 失败兜底上下文，每轮重置（防跨轮残留误触发）
        "delegation_tokens": {},
    }


class ChatRequest(BaseModel):
    """Chat Backend 转发到编排层的请求体。"""

    userId: str = Field(..., description="用户 ID")
    role: str = Field("STUDENT", description="用户角色")
    message: str = Field(..., description="用户消息")
    traceId: str = Field(default="", description="分布式追踪 ID")
    conversationContext: dict[str, Any] = Field(default_factory=dict)
    sessionId: str = Field(default="", description="会话 ID（多轮上下文复用 thread_id）")


class ResumeRequest(BaseModel):
    """Chat Backend 转发的 HITL 确认恢复请求。"""

    userId: str = Field(..., description="用户 ID")
    role: str = Field("STUDENT", description="用户角色")
    sessionId: str = Field(..., description="会话 ID（必须与原始 thread_id 一致）")
    approved: bool = Field(True, description="用户确认结果（true=确认，false=取消）")
    traceId: str = Field(default="", description="分布式追踪 ID")


# ──────────────────────────────────────────────────────────────────────
# 健康检查
# ──────────────────────────────────────────────────────────────────────


@app.get("/health")
async def health():
    return {"status": "ok", "service": "orchestration", "version": "0.1.0"}


# ──────────────────────────────────────────────────────────────────────
# 聊天入口（SSE 流式）
# ──────────────────────────────────────────────────────────────────────


@app.post("/chat/stream")
async def chat_stream(request: Request):
    """接收用户消息，运行 LangGraph，返回 SSE 事件流（token 级流式）。

    安全：入站请求需携带 X-Signature / X-Nonce / X-Timestamp
    （与 Chat Backend OrchestrationClient 共享密钥 HMAC）。
    """
    # 入站安全校验（HMAC + Nonce + Timestamp）
    verified = await _get_inbound_security().verify(request)

    # 解析请求体
    try:
        body = await request.json()
        payload = ChatRequest(**body)
    except Exception:
        raise HTTPException(status_code=400, detail="invalid request body")

    trace_id = payload.traceId or verified.trace_id or str(uuid.uuid4())
    session_id = _sanitize_thread_id(payload.sessionId)

    logger.info(
        "chat_stream: userId=%s, sessionId=%s, traceId=%s", payload.userId, session_id or "(new)", trace_id
    )

    # 构建初始状态（注意：不含 pending_info 重置，澄清循环状态依赖 checkpoint 跨轮保留）
    initial_state: dict[str, Any] = _build_initial_state(payload, trace_id, session_id)

    logger.info("chat_stream: userId=%s, traceId=%s", payload.userId, trace_id)

    graph = _get_graph()

    return StreamingResponse(
        _sse_stream(graph, initial_state, payload.userId, trace_id, session_id),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "X-Trace-Id": trace_id,
        },
    )


@app.post("/chat/resume")
async def chat_resume(request: Request):
    """HITL 确认恢复：以 Command(resume=...) 恢复挂起的 LangGraph，返回 SSE 事件流。

    安全：与 /chat/stream 一致（入站 HMAC + Nonce + Timestamp）。
    resume 必须使用与原始运行一致的 thread_id（sessionId），否则无法找到挂起 checkpoint。
    """
    verified = await _get_inbound_security().verify(request)

    try:
        body = await request.json()
        payload = ResumeRequest(**body)
    except Exception:
        raise HTTPException(status_code=400, detail="invalid request body")

    trace_id = payload.traceId or verified.trace_id or str(uuid.uuid4())
    session_id = _sanitize_thread_id(payload.sessionId)
    if not session_id:
        raise HTTPException(status_code=400, detail="sessionId is required for resume")

    logger.info(
        "chat_resume: userId=%s, sessionId=%s, approved=%s, traceId=%s",
        payload.userId,
        session_id,
        payload.approved,
        trace_id,
    )

    graph = _get_graph()
    thread_id = _sanitize_thread_id(payload.sessionId)

    # ── 所有权 + 中断态校验（兼作 resume 幂等保护）──
    # 1) checkpoint 内 user_id 必须与调用者一致（防 sessionId 横向越权）
    # 2) thread 必须确实停在 human_approval 中断（非中断态/已消费的 resume 一律 409，
    #    杜绝双重提交导致写操作重复执行）
    try:
        snapshot = graph.get_state({"configurable": {"thread_id": thread_id}})
    except Exception:
        snapshot = None
    if not snapshot or not snapshot.next or "human_approval" not in snapshot.next:
        raise HTTPException(status_code=409, detail="session is not awaiting approval")
    state_user = (snapshot.values or {}).get("user_id")
    if state_user is not None and str(state_user) != str(payload.userId):
        logger.warning(
            "chat_resume owner mismatch: session=%s state_user=%s caller=%s",
            thread_id,
            state_user,
            payload.userId,
        )
        raise HTTPException(status_code=403, detail="session owner mismatch")

    return StreamingResponse(
        _sse_stream(
            graph,
            {},  # resume 分支不使用 initial_state（图从 checkpoint 恢复）
            payload.userId,
            trace_id,
            session_id,
            resume={"approved": payload.approved},
        ),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "X-Trace-Id": trace_id,
        },
    )


async def _sse_stream(
    graph,
    initial_state: dict[str, Any],
    user_id: str,
    trace_id: str,
    session_id: str = "",
    resume: dict | None = None,
) -> AsyncGenerator[str, None]:
    """流式执行 LangGraph 并逐事件生成 SSE 文本。

    stream_mode=["messages", "updates"]：
    - messages: 捕获 chat_responder 的 LLM token 增量 → token 事件（打字机）
    - updates:  节点完成增量 → 结构化事件（intent_detected / agent_start / ...）

    空回复兜底策略：
    - chat_responder 的 updates 完整回复先缓冲（pending_chat_reply），不立即发出：
      stream_mode=["messages", "updates"] 是并行流，updates 可能先于 messages 到达，
      若直接发会与逐字 token 流重复（表现为回复出现两遍）；流末若 messages 全程
      无产出才补发完整回复
    - 图异常 / 无节点处理 / 无任何输出 → _direct_llm_reply 直接用 LLM 回复

    多轮上下文：同一会话复用 thread_id（前端 session_id 透传）——
    MemorySaver checkpoint 累积 messages；上次停在中断时由 _thread_config 换新 thread。
    """
    emitted_content = False  # 是否已向用户发出过内容
    emitted_error = False  # 是否已发出错误事件
    chat_streamed = False  # chat_responder 是否已通过 messages 模式逐字发出
    chat_buf = ""  # 已发出的 chat_responder token 拼接（末尾完整重放检测用）
    pending_chat_reply: str | None = None  # updates 模式完整回复的缓冲（流末按需补发）
    stream_failed = False  # 图执行异常（异常路径已由 _direct_llm_reply 兜底，跳过补发）

    try:
        if resume is not None:
            # HITL 确认恢复：必须用原始 thread_id 恢复挂起的图
            # （不能走 _thread_config —— 它会在 thread 停在中断时换新 thread）
            stream = graph.astream(
                Command(resume=resume),
                config={"configurable": {"thread_id": _sanitize_thread_id(session_id)}},
                stream_mode=["messages", "updates"],
            )
        else:
            stream = graph.astream(
                initial_state,
                config=_thread_config(graph, session_id),
                stream_mode=["messages", "updates"],
            )
        async for mode, chunk in stream:
            if mode == "messages":
                # chunk: (message_chunk, metadata)
                message_chunk = chunk[0] if isinstance(chunk, tuple) else chunk
                metadata = chunk[1] if isinstance(chunk, tuple) else {}

                # 只透传 chat_responder 的 token（避免意图分类等中间 LLM 泄漏到前端）
                node = metadata.get("langgraph_node") if isinstance(metadata, dict) else None
                if node != "chat_responder":
                    continue

                content = getattr(message_chunk, "content", None)
                if content and getattr(message_chunk, "type", "") not in ("tool", "function"):
                    # 末尾完整重放检测：LangGraph 在 LLM 结束（on_llm_end）时会把聚合后的
                    # 完整回复再作为一条 chunk 发到 messages 模式（content == 已拼全文），
                    # 前端把分块拼接与完整文本再拼一遍 → 回复出现两遍。丢弃该 chunk。
                    # 不做长度阈值：短回复（如"1+1"→"2"）的完整 chunk 同样会被重放，
                    # 有阈值时漏检（表现为"22"）。取舍：若 LLM 恰好输出"X X"完整重复且
                    # 分块边界恰好落在 X|X，可能误丢后一块——概率极低，且远小于回复两遍的影响。
                    if content == chat_buf:
                        continue
                    chat_buf += content
                    chat_streamed = True
                    emitted_content = True
                    yield _format_sse(SSEEvent("token", {"content": content}))

            elif mode == "updates":
                # chunk: {node_name: update_dict}
                if not isinstance(chunk, dict):
                    continue
                for node_name, update in chunk.items():
                    # Human-in-the-loop：图暂停等待审批（update 是 (interrupt,) 元组）
                    if node_name == "__interrupt__":
                        interrupt_obj = update[0] if isinstance(update, tuple) else update
                        value = getattr(interrupt_obj, "value", interrupt_obj)
                        if isinstance(value, dict):
                            yield _format_sse(
                                SSEEvent(
                                    "confirm_required",
                                    {
                                        "agent": value.get("agent", ""),
                                        "details": value.get("details", {}),
                                        # interrupt 顶层 message（human_approval 的确认提示）透传，
                                        # 前端优先展示；details 里无 message 时回退 summary
                                        "message": value.get("message", ""),
                                    },
                                )
                            )
                            # 中断是正常暂停而非"无输出"：置位以抑制流末 LLM 抢答
                            # （否则用户同时看到确认框和一条 LLM 即时回复）
                            emitted_content = True
                        continue

                    # 防御：LangGraph 对返回空 dict 的节点可能产出 None 更新，
                    # 跳过避免 NoneType.get() 崩溃（空更新无事件可发）
                    if not isinstance(update, dict):
                        continue

                    # chat_responder：token 由 messages 模式逐字发出；此处仅缓冲完整回复，
                    # 流末若 messages 全程无产出（chat_streamed=False）才补发，避免与
                    # 逐字 token 流重复（并行流顺序不保证，updates 可能先于 messages 到达）
                    if node_name == "chat_responder":
                        msgs = update.get("messages") or []
                        if msgs and hasattr(msgs[-1], "content"):
                            content = msgs[-1].content
                            if content:
                                pending_chat_reply = content
                        continue

                    for evt in structural_events_from_update(node_name, update):
                        if evt.event == "token" and evt.data.get("content"):
                            emitted_content = True
                        if evt.event == "error":
                            emitted_error = True
                        yield _format_sse(evt)

    except asyncio.CancelledError:
        logger.info("chat_stream cancelled: userId=%s, traceId=%s", user_id, trace_id)
        raise
    except Exception as e:
        logger.error("chat_stream failed: userId=%s, traceId=%s, err=%s", user_id, trace_id, e)
        stream_failed = True
        if resume is not None:
            # resume 分支异常：不兜底 LLM（initial_state 为空，会输出"你好"），直接发错误事件
            if not emitted_error:
                yield _format_sse(SSEEvent("error", {"message": "恢复会话失败，请重新发起请求"}))
        elif not emitted_content and not emitted_error:
            # 异常兜底：未产出任何内容时直接用 LLM 回复，保证"永远有回复"
            async for evt in _direct_llm_reply(initial_state):
                emitted_content = True
                yield _format_sse(evt)
        elif not emitted_error:
            yield _format_sse(SSEEvent("error", {"message": "编排层处理失败"}))

    # 图正常结束（非异常）才走补发逻辑，避免与异常兜底 _direct_llm_reply 重复
    if not stream_failed:
        # chat_responder 的完整回复仅在 messages 模式全程无产出时补发（防重复）
        if not chat_streamed and pending_chat_reply:
            emitted_content = True
            yield _format_sse(SSEEvent("token", {"content": pending_chat_reply}))

        # 图正常结束但没有任何输出（无节点处理 / 空回复）→ 直接用 LLM 回复；
        # resume 分支例外：无输出即发错误事件（避免用空上下文输出"你好"）
        if not emitted_content and not emitted_error:
            if resume is not None:
                yield _format_sse(SSEEvent("error", {"message": "恢复会话失败，请重新发起请求"}))
            else:
                async for evt in _direct_llm_reply(initial_state):
                    yield _format_sse(evt)

    # 结束事件
    yield _format_sse(SSEEvent("done", {}))


async def _direct_llm_reply(initial_state: dict[str, Any]) -> AsyncGenerator[SSEEvent, None]:
    """兜底：图未产出内容 / 异常时，直接用 LLM 回复用户。

    流式逐 token 产出（astream）；LLM 也失败则无产出
    （由调用方决定是否发 error 事件）。
    """
    try:
        llm = chat_llm()
        messages = initial_state.get("messages") or [HumanMessage(content="你好")]
        warned = False
        async for chunk in llm.astream(messages):
            content = getattr(chunk, "content", "") or ""
            if content.strip():
                if not warned:
                    logger.warning("graph produced no output; fallback to direct LLM reply")
                    warned = True
                yield SSEEvent("token", {"content": content})
    except Exception as e:
        logger.error("direct LLM fallback failed: %s", e)


def _format_sse(evt: SSEEvent) -> str:
    """格式化单个 SSE 事件为标准文本。"""
    lines: list[str] = []
    if evt.id:
        lines.append(f"id: {evt.id}")
    lines.append(f"event: {evt.event}")
    lines.append(f"data: {json.dumps(evt.data, ensure_ascii=False)}")
    lines.append("")
    return "\n".join(lines) + "\n"


# ──────────────────────────────────────────────────────────────────────
# 调试端点
# ──────────────────────────────────────────────────────────────────────


@app.get("/debug/graph")
async def debug_graph(request: Request):
    """返回图结构 ASCII（调试用）。

    与 /chat/stream 同安全级别：需要合法的 X-Signature / X-Nonce / X-Timestamp
    （共享密钥 HMAC；GET 请求签名的 body 为空字符串）。
    """
    await _get_inbound_security().verify(request)
    graph = _get_graph()
    try:
        return {"graph": graph.get_graph().print_ascii()}
    except Exception as e:
        return {"error": str(e)}
