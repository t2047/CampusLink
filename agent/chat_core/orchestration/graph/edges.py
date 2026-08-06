"""LangGraph 条件边路由逻辑 — Chat Core 编排层。

每个函数接收 AgentState，返回下一个节点的名称（字符串）。
"""

from __future__ import annotations

from typing import Literal

from .state import AgentState

RouteResult = Literal["agent_invoker", "utility_tool_executor", "chat_responder"]
AgentAfterResult = Literal["invoke_next", "needs_approval", "to_guard", "to_fallback"]
UtilityAfterResult = Literal["agent_invoker", "to_guard"]
GuardAfterResult = Literal["aggregate", "end"]


def route_by_intent(state: AgentState) -> RouteResult:
    """意图三分支路由。"""
    intent = state.get("intent_type", "chat")
    if intent == "domain_agent":
        return "agent_invoker"
    if intent == "utility":
        return "utility_tool_executor"
    return "chat_responder"


def after_agent_invoke(state: AgentState) -> AgentAfterResult:
    """Agent 调用后的决策：继续下一个 / 等待审批 / 汇聚 / 降级。"""
    # 安全拦截或致命错误 → 直接降级（不进入 Agent）
    if state.get("error"):
        return "to_fallback"

    # 需要审批 → 暂停等待
    if state.get("requires_approval"):
        return "needs_approval"

    # 还有未调用的 Agent → 继续循环
    agent_plan = state.get("agent_plan", [])
    current = state.get("current_agent_index", 0)
    if current < len(agent_plan):
        return "invoke_next"

    return "to_guard"


def after_utility(state: AgentState) -> UtilityAfterResult:
    """Utility 调用后：若还有 Agent 待调用则进入 Agent 路径，否则汇聚。"""
    if state.get("agent_plan"):
        return "agent_invoker"
    return "to_guard"


def after_human_approval(state: AgentState) -> AgentAfterResult:
    """审批结束后：重新进入 Agent 循环。"""
    return "invoke_next"


def after_guardrail(state: AgentState) -> GuardAfterResult:
    """输出护栏后：是否仍有内容需要聚合。"""
    return "aggregate"


def after_fallback(state: AgentState) -> GuardAfterResult:
    """降级处理后进入聚合。"""
    return "aggregate"
