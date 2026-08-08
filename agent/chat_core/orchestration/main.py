"""Chat Core 编排层入口 — FastAPI 应用。

端点：
- GET  /health                       健康检查
- POST /chat/stream                  接收 Chat Backend 请求，跑 LangGraph，返回 SSE 事件流

安全：
- /chat/stream 由编排层入站安全中间件校验（Chat Backend 共享密钥 HMAC 签名 + Nonce 防重放）
- 编排层 → Agent：RS256 Delegation Token 从 Token Service 兑换（当前内嵌于 Chat
  Backend 的 POST /internal/token/exchange；独立部署 Sprint 3+，仅切换 TOKEN_SERVICE_URL）
- Token Service 不可用时回退本地 HS256 签发（AGENT_SHARED_SECRET，仅限联调）

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

多轮对话注意（修复"第二条消息无响应/一直回复中"）：
- 图使用 MemorySaver checkpointer。若每条消息复用同一 thread_id（如 userId），
  LangGraph 会把第二条消息当作"恢复上次运行"：
    * 上次停在 interrupt（如 human_approval）→ 恢复需要 Command(resume=...)，
      永远等不到 → astream 挂起、无任何事件 → 前端一直"回复中"
    * 即使上次正常结束，也可能复用旧 checkpoint 状态导致行为异常
- 因此每条请求使用独立 thread_id（uuid4）：每次都是全新运行，杜绝恢复挂起。
  后续要做真正的多轮对话 + HITL 恢复时，再改为由前端透传会话 thread_id
  并在中断后用 Command(resume=...) 恢复。
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any, AsyncGenerator

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage
from pydantic import BaseModel, Field

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


class ChatRequest(BaseModel):
    """Chat Backend 转发到编排层的请求体。"""

    userId: str = Field(..., description="用户 ID")
    role: str = Field("STUDENT", description="用户角色")
    message: str = Field(..., description="用户消息")
    traceId: str = Field(default="", description="分布式追踪 ID")
    conversationContext: dict[str, Any] = Field(default_factory=dict)


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

    # 构建初始状态
    initial_state: dict[str, Any] = {
        "messages": [HumanMessage(content=payload.message)],
        "intent_type": None,
        "targets": [],
        "agent_plan": [],
        "utility_plan": [],
        "current_agent_index": 0,
        "user_id": payload.userId,
        "user_role": payload.role,
        "trace_id": trace_id,
        "conversation_context": payload.conversationContext,
        "requires_approval": False,
        "error": None,
        "failed_agents": [],
        "delegation_tokens": {},
    }

    logger.info("chat_stream: userId=%s, traceId=%s", payload.userId, trace_id)

    graph = _get_graph()

    return StreamingResponse(
        _sse_stream(graph, initial_state, payload.userId, trace_id),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "X-Trace-Id": trace_id,
        },
    )


async def _sse_stream(graph, initial_state: dict[str, Any], user_id: str, trace_id: str) -> AsyncGenerator[str, None]:
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

    多轮安全：每条请求使用独立 thread_id（uuid4），避免 MemorySaver checkpoint
    复用导致第二条消息被当作"恢复上次运行"而挂起（详见模块 docstring）。
    """
    emitted_content = False   # 是否已向用户发出过内容
    emitted_error = False     # 是否已发出错误事件
    chat_streamed = False     # chat_responder 是否已通过 messages 模式逐字发出
    chat_buf = ""             # 已发出的 chat_responder token 拼接（末尾完整重放检测用）
    pending_chat_reply: str | None = None  # updates 模式完整回复的缓冲（流末按需补发）
    stream_failed = False     # 图执行异常（异常路径已由 _direct_llm_reply 兜底，跳过补发）

    try:
        async for mode, chunk in graph.astream(
            initial_state,
            # 关键：每条消息独立 thread_id，杜绝 checkpoint 复用挂起。
            # （不要用 user_id，否则第二条消息会尝试恢复上次中断的运行）
            config={"configurable": {"thread_id": str(uuid.uuid4())}},
            stream_mode=["messages", "updates"],
        ):
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
                    # 末尾完整重放检测：LLM 流末尾可能重放聚合后的完整文本
                    # （chunk 内容 == 已拼全文 → 前端把分块拼接与完整文本再拼一遍，
                    #  回复出现两遍）。丢弃该 chunk。
                    # 阈值 8：低于该长度不判重放，避免误伤"你好你好"这类正常重复词块。
                    # 取舍：内容本身为长重复块（每块 ≥8 且完全相同）时可能误丢，概率极低
                    # （且 ainvoke 已消除重放源头，此处仅为双保险），可接受。
                    if len(chat_buf) >= 8 and content == chat_buf:
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
                            yield _format_sse(SSEEvent("confirm_required", {
                                "agent": value.get("agent", ""),
                                "details": value.get("details", {}),
                            }))
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
        # 异常兜底：未产出任何内容时直接用 LLM 回复，保证"永远有回复"
        if not emitted_content and not emitted_error:
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

        # 图正常结束但没有任何输出（无节点处理 / 空回复）→ 直接用 LLM 回复
        if not emitted_content and not emitted_error:
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
