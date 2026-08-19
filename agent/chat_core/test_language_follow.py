"""语言跟随回归测试:失败兜底文案必须跟随用户消息语言(2026-08-15 修复)。

背景: 英文输入 + 子 Agent 失败时,chat_responder/fallback_handler 此前固定
输出中文兜底,导致"回复与输入不匹配"。修复后按用户消息语言中英文切换。
"""

from langchain_core.messages import HumanMessage

from orchestration.graph.nodes import (
    chat_responder,
    fallback_handler,
    human_approval,
    utility_tool_executor,
)


def test_fallback_handler_en_input_outputs_english() -> None:
    result = fallback_handler(
        {
            "messages": [
                HumanMessage(
                    content="I just found a student card on the third floor corridor of ISS College."
                )
            ],
            "failed_agents": ["lost-found-agent"],
        }
    )
    text = result["messages"][-1].content
    assert "temporarily unavailable" in text
    assert "暂时" not in text


def test_fallback_handler_zh_input_outputs_chinese() -> None:
    result = fallback_handler(
        {
            "messages": [HumanMessage(content="我丢了一串钥匙")],
            "failed_agents": ["lost-found-agent"],
        }
    )
    text = result["messages"][-1].content
    assert "暂时不可用" in text


def test_fallback_handler_no_failed_agents_follows_language() -> None:
    en = fallback_handler({"messages": [HumanMessage(content="I lost my wallet.")], "failed_agents": []})
    assert "temporarily unavailable" in en["messages"][-1].content

    zh = fallback_handler({"messages": [HumanMessage(content="你好")], "failed_agents": []})
    assert "暂时不可用" in zh["messages"][-1].content


async def test_chat_responder_error_branch_follows_language() -> None:
    """error 分支不调用 LLM,直接验证语言跟随。"""
    en = await chat_responder(
        {
            "messages": [HumanMessage(content="I just found a student card.")],
            "error": "detected_prompt_injection",
        }
    )
    assert "Sorry, I did not understand" in en["messages"][-1].content

    zh = await chat_responder(
        {"messages": [HumanMessage(content="我捡到一张学生卡")], "error": "detected_prompt_injection"}
    )
    assert "没有理解" in zh["messages"][-1].content


async def test_web_search_confirmation_follows_language() -> None:
    """联网搜索确认门文案跟随用户语言（2026-08-17 修复：此前固定中文）。"""
    # 英文输入 → 英文确认文案
    en = await utility_tool_executor(
        {
            "messages": [HumanMessage(content="What's the news?")],
            "utility_plan": ["web_search"],
        },
        client=object(),  # 确认门分支不触碰 client，仅避免真实创建
    )
    assert en["requires_approval"] is True
    en_msg = en["approval_context"]["message"]
    assert "About to search the web" in en_msg and "Continue?" in en_msg
    assert "即将" not in en_msg
    assert en["approval_context"]["summary"] == "Web search"

    # 中文输入 → 中文确认文案
    zh = await utility_tool_executor(
        {
            "messages": [HumanMessage(content="最近有什么新闻")],
            "utility_plan": ["web_search"],
        },
        client=object(),
    )
    assert "即将联网搜索" in zh["approval_context"]["message"]
    assert zh["approval_context"]["summary"] == "联网搜索"


def test_human_approval_fallback_message_follows_language(monkeypatch) -> None:
    """human_approval 兜底确认文案（无 message/summary 时）跟随用户语言。"""
    import langgraph.types as lt

    captured: dict = {}

    def fake_interrupt(payload):
        captured["payload"] = payload
        return {"approved": True}

    monkeypatch.setattr(lt, "interrupt", fake_interrupt)

    human_approval(
        {
            "messages": [HumanMessage(content="Cancel my booking.")],
            "approval_agent": "lost-found-agent",
            "approval_context": {},
        }
    )
    assert captured["payload"]["message"] == "Please confirm this action"

    human_approval(
        {
            "messages": [HumanMessage(content="帮我取消预约")],
            "approval_agent": "lost-found-agent",
            "approval_context": {},
        }
    )
    assert captured["payload"]["message"] == "请确认此操作"


def test_human_approval_cancel_response_follows_language(monkeypatch) -> None:
    """用户取消 Agent 操作时 output_response 跟随用户语言。"""
    import langgraph.types as lt

    monkeypatch.setattr(lt, "interrupt", lambda payload: {"approved": False})

    en = human_approval(
        {
            "messages": [HumanMessage(content="Cancel my booking.")],
            "approval_agent": "lost-found-agent",
            "approval_context": {},
            "agent_invocations": [{"output_status": "pending"}],
        }
    )
    assert en["agent_invocations"][-1]["output_response"] == "The operation has been cancelled."

    zh = human_approval(
        {
            "messages": [HumanMessage(content="取消预约")],
            "approval_agent": "lost-found-agent",
            "approval_context": {},
            "agent_invocations": [{"output_status": "pending"}],
        }
    )
    assert zh["agent_invocations"][-1]["output_response"] == "操作已取消。"
