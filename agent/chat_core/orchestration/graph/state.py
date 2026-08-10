"""AgentState 定义 — Chat Core 编排层。

LangGraph 核心数据模型。所有节点通过读写该状态协作。

字段分组：
- 消息流:      messages
- 意图路由:    intent_type / targets / agent_plan / utility_plan
- 执行追踪:    agent_invocations / current_agent_index / utility_results
- HITL:       requires_approval / approval_context / approval_agent
- 安全上下文:  user_id / user_role / delegation_tokens / nonce / trace_id
- 降级:        error / failed_agents
- 跨 Agent:    conversation_context
"""

from __future__ import annotations

from typing import Annotated, Any, Optional, Sequence, TypedDict

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages


class AgentInvocation(TypedDict, total=False):
    """一次 Agent 调用的完整记录。"""

    agent_name: str
    input_message: str
    output_response: str
    output_status: str                    # completed | needs_confirmation | failed | cancelled | confirmed
    confirmation_required: dict[str, Any] | None
    shared_context: dict[str, Any]        # 跨 Agent 传递的结构化数据
    actions_taken: list[dict[str, Any]]   # [{action, status, result}]
    request_id: str | None                # Domain Agent request ID（排障关联）
    error: str | None


class AgentState(TypedDict, total=False):
    """编排层核心状态。"""

    # ── 对话消息流 ──
    messages: Annotated[Sequence[BaseMessage], add_messages]

    # ── 意图路由 ──
    intent_type: Optional[str]            # "domain_agent" | "utility" | "chat"
    targets: list[str]
    agent_plan: list[str]
    utility_plan: list[str]

    # ── 执行追踪 ──
    agent_invocations: list[AgentInvocation]
    current_agent_index: int
    utility_results: dict[str, dict[str, Any]]

    # ── Human-in-the-loop ──
    requires_approval: bool
    approval_context: dict[str, Any] | None
    approval_agent: str | None
    # 确认后的重调标记：{"agent_name": ..., "confirmation_id": ...}，
    # agent_invoker 下次调用该 Agent 时携带 confirmed=True + confirmation_id
    pending_confirmation: dict[str, Any] | None

    # ── 安全上下文 ──
    user_id: str | None
    user_role: str | None
    delegation_tokens: dict[str, str]     # agent_name → delegation token
    nonce: str | None
    trace_id: str | None
    session_id: str | None                # 会话 ID（传给 Agent 做 per_session 限流/上下文）

    # ── 跨 Agent 上下文 ──
    conversation_context: dict[str, Any]

    # ── 降级 ──
    error: str | None
    failed_agents: list[str]
    service_failures: list[str]           # 工具/子 Agent 失败描述（转主 Agent 兜底）
