"""测试 — 意图路由（LLM 语义分类）。

意图分类完全由 LLM 判定（不再有关键词规则预判）：
- 规则级关键词已移除（无法理解"不要用计算器"这类否定语境）
- LLM 路径通过注入 fake llm 对象验证（不发起真实网络调用）
- LLM 失败/超时/返回非 JSON → 安全降级为 chat（不误调 Agent/Utility）

注意：节点现在返回"最小状态增量"（不再是全量 state），
测试断言的是返回的增量 dict 中的字段。
"""

from __future__ import annotations

import json

from langchain_core.messages import AIMessage, HumanMessage

from orchestration.graph.nodes import intent_router
from orchestration.graph.state import AgentState


class FakeLLM:
    """模拟 langchain LLM：返回预设 content。"""

    def __init__(self, content: str):
        self._content = content

    def invoke(self, messages):
        return AIMessage(content=self._content)


def make_state(message: str) -> AgentState:
    return AgentState(
        messages=[HumanMessage(content=message)],
        intent_type=None,
        targets=[],
        agent_plan=[],
        utility_plan=[],
        current_agent_index=0,
    )


def test_intent_router_llm_chat(monkeypatch):
    state = make_state("今天天气怎么样")
    fake = FakeLLM(json.dumps({"intent_type": "chat", "targets": [], "reasoning": "闲聊"}))
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "chat"
    assert result["agent_plan"] == []
    assert result["utility_plan"] == []


def test_intent_router_llm_domain_agent(monkeypatch):
    state = make_state("帮我找一下张三的邮件")
    fake = FakeLLM(json.dumps({
        "intent_type": "domain_agent",
        "targets": ["mail-agent"],
        "reasoning": "用户要查邮件",
    }))
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "domain_agent"
    assert result["agent_plan"] == ["mail-agent"]
    assert result["utility_plan"] == []


def test_intent_router_llm_utility(monkeypatch):
    state = make_state("把 15 美元换算成人民币")
    fake = FakeLLM(json.dumps({
        "intent_type": "utility",
        "targets": ["unit_converter"],
        "reasoning": "单位换算",
    }))
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "utility"
    assert result["utility_plan"] == ["unit_converter"]
    assert result["agent_plan"] == []


def test_intent_router_llm_multi_target(monkeypatch):
    state = make_state("邮件里提到的会议室帮我订了")
    fake = FakeLLM(json.dumps({
        "intent_type": "domain_agent",
        "targets": ["mail-agent", "facility-agent"],
        "reasoning": "先查邮件再订会议室",
    }))
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "domain_agent"
    assert set(result["agent_plan"]) == {"mail-agent", "facility-agent"}


def test_intent_router_negation_delegated_to_llm(monkeypatch):
    """否定语境（'不要用计算器'）交给 LLM 语义判定：LLM 判 chat → 路由 chat。"""
    state = make_state("不要用计算器，2+2 等于几")
    fake = FakeLLM(json.dumps({
        "intent_type": "chat",
        "targets": [],
        "reasoning": "用户明确拒绝使用计算工具，直接回答",
    }))
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "chat"
    assert result["agent_plan"] == []
    assert result["utility_plan"] == []


def test_intent_router_llm_invalid_json_falls_back_to_chat(monkeypatch):
    state = make_state("今天天气怎么样")
    fake = FakeLLM("不是 JSON 的响应文本")
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "chat"
    assert result["agent_plan"] == []


def test_intent_router_llm_raises_falls_back_to_chat(monkeypatch):
    """LLM 抛异常（网络/超时）→ 安全降级 chat，不误调工具。"""

    class RaisingLLM:
        def invoke(self, messages):
            raise RuntimeError("llm down")

    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: RaisingLLM())
    result = intent_router(make_state("帮我订个研讨室"))
    assert result["intent_type"] == "chat"
    assert result["agent_plan"] == []
    assert result["utility_plan"] == []
