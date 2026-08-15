"""测试 — 图条件边路由：工具/子 Agent 失败时转主 Agent（chat_responder）兜底。

覆盖：
1. after_utility：utility 全部失败 → to_chat；部分失败 → 照常汇聚；有 Agent 待调 → agent_invoker
2. after_agent_invoke：全部子 Agent 失败 → to_chat；部分失败 → 照常汇聚
"""

from __future__ import annotations

from orchestration.graph.edges import after_agent_invoke, after_utility


def test_after_utility_all_failed_to_chat():
    """utility 工具全部失败 → 转主 Agent（LLM）兜底。"""
    state = {
        "utility_results": {
            "get_current_time": {"status": "failed", "error": "MCP service ... unreachable"},
        }
    }
    assert after_utility(state) == "to_chat"


def test_after_utility_partial_failed_to_guard():
    """部分成功部分失败 → 照常汇聚（失败项显示友好文案，不暴露技术错误）。"""
    state = {
        "utility_results": {
            "calculator": {"status": "completed", "result": 1},
            "get_current_time": {"status": "failed", "error": "..."},
        }
    }
    assert after_utility(state) == "to_guard"


def test_after_utility_with_pending_agent():
    """utility 完成后还有 Agent 待调用 → 进入 Agent 路径。"""
    state = {
        "utility_results": {"calculator": {"status": "completed", "result": 1}},
        "agent_plan": ["mail-agent"],
    }
    assert after_utility(state) == "agent_invoker"


def test_after_agent_invoke_all_failed_to_chat():
    """全部子 Agent 失败 → 转主 Agent（LLM）兜底。"""
    state = {
        "agent_plan": ["mail-agent"],
        "current_agent_index": 1,
        "failed_agents": ["mail-agent"],
    }
    assert after_agent_invoke(state) == "to_chat"


def test_after_agent_invoke_partial_failed_to_guard():
    """部分失败部分成功 → 照常汇聚。"""
    state = {
        "agent_plan": ["mail-agent", "facility-agent"],
        "current_agent_index": 2,
        "failed_agents": ["mail-agent"],
    }
    assert after_agent_invoke(state) == "to_guard"


def test_after_agent_invoke_incomplete_continues():
    """还有 Agent 未调用 → 继续循环。"""
    state = {
        "agent_plan": ["mail-agent", "facility-agent"],
        "current_agent_index": 0,
        "failed_agents": [],
    }
    assert after_agent_invoke(state) == "invoke_next"


def test_after_utility_requires_approval_routes_to_human_approval():
    """联网搜索确认门：requires_approval → after_utility 返回 human_approval，
    且 graph 的 utility 路由映射必须包含该键（2026-08-15 回归：映射缺
    human_approval 键 → LangGraph KeyError → 图异常 → 兜底闲聊"知识库截止"）。"""
    from orchestration.graph.edges import after_utility
    from orchestration.graph.graph import _UTILITY_AFTER_ROUTES

    state = {"requires_approval": True, "utility_plan": ["web_search"], "utility_results": {}}
    assert after_utility(state) == "human_approval"
    # 映射表必须覆盖 after_utility 的全部返回值（防 KeyError）
    assert _UTILITY_AFTER_ROUTES["human_approval"] == "human_approval"
