"""测试 — _sse_stream token 去重（回归：回复出现两遍）。

真实 bug 场景：chat_responder 用 ``llm.astream`` 手动聚合时，messages 模式捕获的
token 流末尾会出现一个**完整文本 chunk**（astream 在流结束时重放聚合后的全文，
其内容 == 已拼全文）。前端把"分块拼接的全文"与"末尾完整 chunk"再拼一次 →
回复显示两遍。事件流形态：分块…完整文本（最后）。

修复（main.py）：
1. chat_responder 改回 ``ainvoke``（从源头消除末尾完整 chunk）
2. messages 分支加末尾重放检测：chunk 内容 == 已拼全文（长度 ≥ 8）时丢弃

覆盖场景：
1. 分块流末尾混入完整文本 chunk → 末尾被丢弃（真实 bug）
2. 短重复词块（"你好你好"）不被误判丢弃
3. updates 先于 messages 到达 → 完整回复只发一次（防御）
4. 分块已产出 → updates 完整回复不补发
5. 仅 messages → 正常逐字流
6. 仅 updates（messages 全程未捕获）→ 流末补发完整回复一次
"""

from __future__ import annotations

import asyncio
import json

from langchain_core.messages import AIMessage, AIMessageChunk

import orchestration.main as main


class FakeGraph:
    """按给定顺序产出 (mode, chunk) 的假图。"""

    def __init__(self, chunks: list[tuple]):
        self._chunks = chunks

    async def astream(self, initial_state, config=None, stream_mode=None):
        for chunk in self._chunks:
            yield chunk


def _chat_update(content: str) -> tuple:
    return ("updates", {"chat_responder": {"messages": [AIMessage(content=content)]}})


def _chat_token(content: str) -> tuple:
    return (
        "messages",
        (AIMessageChunk(content=content), {"langgraph_node": "chat_responder"}),
    )


def _run(chunks: list[tuple], monkeypatch) -> list[str]:
    graph = FakeGraph(chunks)

    # LLM 兜底置空：本测试聚焦 token 去重，不触发真实 LLM
    async def _no_reply(*args, **kwargs):  # pragma: no cover
        if False:
            yield None

    monkeypatch.setattr(main, "_direct_llm_reply", _no_reply)

    tokens: list[str] = []

    async def consume():
        async for sse_text in main._sse_stream(graph, {}, "u1", "t1"):
            if "event: token" not in sse_text:
                continue
            data = sse_text.split("data: ", 1)[1].strip()
            tokens.append(json.loads(data).get("content", ""))

    asyncio.run(consume())
    return tokens


CONTENT = "好的！你想再来一次什么？😄"  # 14 字符（≥ 重放检测阈值 8）


def test_messages_trailing_full_replay_dropped(monkeypatch):
    """真实 bug：分块流末尾混入完整文本 chunk（== 已拼全文）→ 丢弃，只发分块。"""
    tokens = _run(
        [
            _chat_token("好的"),
            _chat_token("！"),
            _chat_token("你想再来一次什么？😄"),
            _chat_token(CONTENT),  # 末尾完整重放（== 已拼全文）
        ],
        monkeypatch,
    )
    assert tokens == ["好的", "！", "你想再来一次什么？😄"]


def test_repeated_short_chunks_not_dropped(monkeypatch):
    """'你好你好'（短重复词块）不应被误判为完整重放丢弃。"""
    tokens = _run([_chat_token("你好"), _chat_token("你好")], monkeypatch)
    assert tokens == ["你好", "你好"]


def test_updates_before_messages_no_duplicate(monkeypatch):
    """防御场景：即使 updates 先于 messages 到达，完整回复只出现一次（分块流）。"""
    tokens = _run([_chat_update(CONTENT), _chat_token(CONTENT)], monkeypatch)
    assert tokens == [CONTENT]  # 不是 [CONTENT, CONTENT]


def test_messages_then_updates_no_pending_fallback(monkeypatch):
    """分块已产出（chat_streamed=True）→ updates 完整回复不补发。"""
    tokens = _run(
        [_chat_token("好的"), _chat_token("！"), _chat_update(CONTENT)],
        monkeypatch,
    )
    assert tokens == ["好的", "！"]  # updates 的完整回复被缓冲且不补发


def test_messages_only_streams_tokens(monkeypatch):
    """仅 messages：正常逐字流，不补发完整回复。"""
    tokens = _run([_chat_token("测试"), _chat_token("成功！")], monkeypatch)
    assert "".join(tokens) == "测试成功！"
    assert len(tokens) == 2


def test_updates_only_falls_back_once(monkeypatch):
    """仅 updates（messages 全程未捕获）：流末补发完整回复一次。"""
    tokens = _run([_chat_update(CONTENT)], monkeypatch)
    assert tokens == [CONTENT]
