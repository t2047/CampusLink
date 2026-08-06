"""LangGraph 图组装 — Chat Core 编排层。

节点依赖注入说明：
- 节点函数签名支持 (state) 或 (state, **deps)，LangGraph 对超出部分
  通过 functools.partial / 闭包注入，保证纯函数可测试性。
"""

from __future__ import annotations

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from .edges import (
    after_agent_invoke,
    after_fallback,
    after_guardrail,
    after_human_approval,
    after_utility,
    route_by_intent,
)
from .nodes import (
    agent_invoker,
    chat_responder,
    fallback_handler,
    human_approval,
    input_guardrail,
    intent_router,
    output_guardrail,
    response_aggregator,
    utility_tool_executor,
)
from .state import AgentState

# 路由映射表（条件边 → 目标节点）
_INTENT_ROUTES = {
    "agent_invoker": "agent_invoker",
    "utility_tool_executor": "utility_tool_executor",
    "chat_responder": "chat_responder",
}

_AGENT_AFTER_ROUTES = {
    "invoke_next": "agent_invoker",
    "needs_approval": "human_approval",
    "to_guard": "output_guardrail",
    "to_fallback": "fallback_handler",
}

_UTILITY_AFTER_ROUTES = {
    "agent_invoker": "agent_invoker",
    "to_guard": "output_guardrail",
}

_GUARD_AFTER_ROUTES = {
    "aggregate": "response_aggregator",
    "end": END,
}


def build_graph() -> StateGraph:
    """组装并编译完整的编排层 LangGraph。

    返回已 compile() 的图，支持 checkpoint（多轮对话 + HITL 断点恢复）。
    """
    builder = StateGraph(AgentState)

    # ── 节点注册 ──
    builder.add_node("input_guardrail", input_guardrail)
    builder.add_node("intent_router", intent_router)
    builder.add_node("agent_invoker", agent_invoker)
    builder.add_node("utility_tool_executor", utility_tool_executor)
    builder.add_node("chat_responder", chat_responder)
    builder.add_node("output_guardrail", output_guardrail)
    builder.add_node("response_aggregator", response_aggregator)
    builder.add_node("human_approval", human_approval)
    builder.add_node("fallback_handler", fallback_handler)

    # ── 入口 ──
    builder.set_entry_point("input_guardrail")
    builder.add_edge("input_guardrail", "intent_router")

    # ── 意图三分支 ──
    builder.add_conditional_edges("intent_router", route_by_intent, _INTENT_ROUTES)

    # ── Agent 路径（循环 / 审批 / 汇聚 / 降级）──
    builder.add_conditional_edges("agent_invoker", after_agent_invoke, _AGENT_AFTER_ROUTES)
    builder.add_conditional_edges("human_approval", after_human_approval, {
        "invoke_next": "agent_invoker",
    })

    # ── Utility 路径 ──
    builder.add_conditional_edges("utility_tool_executor", after_utility, _UTILITY_AFTER_ROUTES)

    # ── 汇聚 ──
    builder.add_edge("chat_responder", "output_guardrail")
    builder.add_conditional_edges("output_guardrail", after_guardrail, _GUARD_AFTER_ROUTES)
    builder.add_conditional_edges("fallback_handler", after_fallback, _GUARD_AFTER_ROUTES)
    builder.add_edge("response_aggregator", END)

    # ── Checkpointer：多轮对话 + HITL 断点恢复 ──
    memory = MemorySaver()
    return builder.compile(checkpointer=memory)


def visualize_graph(graph: StateGraph) -> str:
    """输出图的 ASCII 结构（调试用）。"""
    try:
        return graph.get_graph().print_ascii()
    except Exception as e:  # pragma: no cover
        return f"graph visualization unavailable: {e}"
