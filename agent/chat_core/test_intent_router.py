"""测试 — 意图路由（规则预判 + LLM 兜底）。

覆盖：关键词规则命中 / LLM JSON 解析 / 异常兜底 / 各意图分支状态写入。
LLM 路径通过注入 fake llm 对象验证（不发起真实网络调用）。

注意：节点现在返回"最小状态增量"（不再是全量 state），
测试断言的是返回的增量 dict 中的字段。
"""

from __future__ import annotations

import json

import pytest
from langchain_core.messages import AIMessage, HumanMessage

from orchestration.graph.nodes import _rule_based_intent, intent_router
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


@pytest.mark.parametrize(
    "message,expected_intent,expected_target",
    [
        ("帮我找一下张三的邮件", "domain_agent", "mail-agent"),
        ("明天下午有没有研讨室", "domain_agent", "facility-agent"),
        ("我丢了黑色的双肩包", "domain_agent", "lost-found-agent"),
        ("有没有人教 Python", "domain_agent", "skill-agent"),
        ("1+1等于几", "utility", "calculator"),
        ("现在几点了", "utility", "get_current_time"),
        ("10公里等于多少英里", "utility", "unit_converter"),
        ("把这段翻译成英文", "utility", "text_translator"),
        ("搜一下最近的新闻", "utility", "web_search"),
    ],
)
def test_rule_based_intent(message, expected_intent, expected_target):
    result = _rule_based_intent(message)
    assert result is not None
    intent, targets = result
    assert intent == expected_intent
    assert expected_target in targets


def test_rule_based_intent_miss_returns_none():
    assert _rule_based_intent("今天天气怎么样") is None
    assert _rule_based_intent("你好呀") is None


def test_intent_router_rule_hit_sets_state():
    state = make_state("帮我找张三的邮件")
    result = intent_router(state)
    assert result["intent_type"] == "domain_agent"
    assert "mail-agent" in result["agent_plan"]
    assert result["utility_plan"] == []
    assert result["current_agent_index"] == 0


def test_intent_router_llm_valid_json(monkeypatch):
    state = make_state("今天天气怎么样")
    fake = FakeLLM(json.dumps({"intent_type": "chat", "targets": [], "reasoning": "闲聊"}))
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "chat"
    assert result["agent_plan"] == []
    assert result["utility_plan"] == []


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


def test_intent_router_llm_invalid_json_falls_back_to_chat(monkeypatch):
    state = make_state("今天天气怎么样")
    fake = FakeLLM("不是 JSON 的响应文本")
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)
    result = intent_router(state)
    assert result["intent_type"] == "chat"
    assert result["agent_plan"] == []
