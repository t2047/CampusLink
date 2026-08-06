"""SSE 流式处理器 — Chat Core 编排层。

将编排层执行结果转为标准 SSE 事件流，供 Chat Backend 透传给前端。

事件类型（对齐通信安全说明文档 / sse-protocol）：
- intent_detected / agent_start / agent_step / agent_done
- token / utility_start / utility_result
- confirm_required / agent_error / done
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator, Optional

from ..graph.state import AgentState


@dataclass
class SSEEvent:
    """单个 SSE 事件。"""

    event: str
    data: dict[str, Any] = field(default_factory=dict)
    id: Optional[str] = None


class OrchestrationStreamer:
    """编排层 SSE 流处理器（基于最终状态构建事件序列，兼容模式）。"""

    def __init__(self, state: AgentState):
        self.state = state

    def build_events(self) -> list[SSEEvent]:
        """根据最终状态构建完整 SSE 事件序列（含最终 token 事件）。"""
        events: list[SSEEvent] = []

        intent = self.state.get("intent_type", "chat")
        events.append(SSEEvent("intent_detected", {"intent_type": intent, "targets": self.state.get("targets", [])}))

        # Agent 调用事件
        for inv in self.state.get("agent_invocations", []):
            agent_name = inv.get("agent_name", "")
            events.append(SSEEvent("agent_start", {"agent": agent_name}))

            for action in inv.get("actions_taken", []):
                events.append(SSEEvent("agent_step", {
                    "agent": agent_name,
                    "action": action.get("action"),
                    "status": action.get("status", "ok"),
                }))

            status = inv.get("output_status", "")
            if status == "needs_confirmation":
                events.append(SSEEvent("confirm_required", {
                    "agent": agent_name,
                    "details": inv.get("confirmation_required", {}),
                }))
            elif status == "failed":
                events.append(SSEEvent("agent_error", {
                    "agent": agent_name,
                    "message": inv.get("output_response", "调用失败"),
                }))
            else:
                events.append(SSEEvent("agent_done", {"agent": agent_name}))

        # Utility 事件
        for tool_name, result in self.state.get("utility_results", {}).items():
            events.append(SSEEvent("utility_start", {"tool": tool_name}))
            events.append(SSEEvent("utility_result", {"tool": tool_name, "result": result}))

        # 最终回复（chat 或聚合后的消息）
        messages = self.state.get("messages", [])
        if messages and hasattr(messages[-1], "content"):
            events.append(SSEEvent("token", {"content": messages[-1].content}))

        events.append(SSEEvent("done", {}))
        return events

    def to_sse_string(self) -> str:
        """将事件序列格式化为标准 SSE 文本。"""
        lines: list[str] = []
        for ev in self.build_events():
            if ev.id:
                lines.append(f"id: {ev.id}")
            lines.append(f"event: {ev.event}")
            lines.append(f"data: {json.dumps(ev.data, ensure_ascii=False)}")
            lines.append("")
        return "\n".join(lines)


def structural_events_from_update(node_name: str, update: dict[str, Any]) -> list[SSEEvent]:
    """将单个节点的状态增量转换为结构化 SSE 事件。

    用于 ``stream_mode="updates"`` 模式：节点完成时立即推送结构事件
    （intent_detected / agent_start / agent_step / utility_* / 最终 token），
    与 ``stream_mode="messages"`` 的 LLM token 流配合，实现真实流式体验。

    节点处理说明：
    - intent_router / agent_invoker / utility_tool_executor：结构化事件
    - response_aggregator / fallback_handler：聚合结果不走 LLM 流式，
      直接把最终回复作为 token 事件发出（避免 Agent/Utility 路径无输出）
    - chat_responder：token 已由 messages 模式逐字推送，此处不再重复发出
    """
    events: list[SSEEvent] = []

    # 防御：LangGraph 可能产出 None 更新（节点返回空/无增量）→ 无事件可发
    if not isinstance(update, dict):
        return events

    if node_name == "intent_router":
        events.append(SSEEvent("intent_detected", {
            "intent_type": update.get("intent_type", "chat"),
            "targets": update.get("targets", []),
        }))

    elif node_name == "agent_invoker":
        invs = update.get("agent_invocations", [])
        if invs:
            inv = invs[-1]  # 本次新增的调用记录
            agent = inv.get("agent_name", "")
            events.append(SSEEvent("agent_start", {"agent": agent}))

            for action in inv.get("actions_taken", []):
                events.append(SSEEvent("agent_step", {
                    "agent": agent,
                    "action": action.get("action"),
                    "status": action.get("status", "ok"),
                }))

            status = inv.get("output_status", "")
            if status == "needs_confirmation":
                events.append(SSEEvent("confirm_required", {
                    "agent": agent,
                    "details": inv.get("confirmation_required", {}),
                }))
            elif status == "failed":
                events.append(SSEEvent("agent_error", {
                    "agent": agent,
                    "message": inv.get("output_response", "调用失败"),
                }))
            else:
                events.append(SSEEvent("agent_done", {"agent": agent}))

    elif node_name == "utility_tool_executor":
        for tool_name, result in (update.get("utility_results", {}) or {}).items():
            events.append(SSEEvent("utility_start", {"tool": tool_name}))
            events.append(SSEEvent("utility_result", {"tool": tool_name, "result": result}))

    elif node_name in ("response_aggregator", "fallback_handler"):
        # 聚合/降级节点直接生成最终 AIMessage（无 LLM 流式）→ 作为单条 token 发出
        messages = update.get("messages") or []
        if messages and hasattr(messages[-1], "content"):
            content = messages[-1].content
            if content:
                events.append(SSEEvent("token", {"content": content}))

    return events


async def astream_events(state: AgentState) -> AsyncGenerator[str, None]:
    """异步版本：逐事件 yield（兼容模式，Sprint 3+ 流式迁移用）。"""
    streamer = OrchestrationStreamer(state)
    for ev in streamer.build_events():
        parts = []
        if ev.id:
            parts.append(f"id: {ev.id}")
        parts.append(f"event: {ev.event}")
        parts.append(f"data: {json.dumps(ev.data, ensure_ascii=False)}")
        parts.append("")
        yield "\n".join(parts)
