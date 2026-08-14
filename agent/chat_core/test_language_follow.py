"""语言跟随回归测试:失败兜底文案必须跟随用户消息语言(2026-08-15 修复)。

背景: 英文输入 + 子 Agent 失败时,chat_responder/fallback_handler 此前固定
输出中文兜底,导致"回复与输入不匹配"。修复后按用户消息语言中英文切换。
"""

from langchain_core.messages import HumanMessage

from orchestration.graph.nodes import chat_responder, fallback_handler


def test_fallback_handler_en_input_outputs_english() -> None:
    result = fallback_handler(
        {
            "messages": [HumanMessage(content="I just found a student card on the third floor corridor of ISS College.")],
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
        {"messages": [HumanMessage(content="I just found a student card.")], "error": "detected_prompt_injection"}
    )
    assert "Sorry, I did not understand" in en["messages"][-1].content

    zh = await chat_responder(
        {"messages": [HumanMessage(content="我捡到一张学生卡")], "error": "detected_prompt_injection"}
    )
    assert "没有理解" in zh["messages"][-1].content
