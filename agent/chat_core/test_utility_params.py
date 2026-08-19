"""测试 — Utility 工具搜索词提取与回指复用（_extract_utility_params）。

覆盖：引导词剥离、"再查一下/继续"等回指请求复用上一次实际查询词
（state.last_search_query），修复回指请求被当作字面查询词搜索的问题。
"""

from langchain_core.messages import HumanMessage

from orchestration.graph.nodes import _extract_utility_params


def _state(msg: str, last_search_query: str | None = None) -> dict:
    return {"messages": [HumanMessage(content=msg)], "last_search_query": last_search_query}


def test_direct_query_passes_through() -> None:
    assert _extract_utility_params("web_search", _state("校园活动有什么更新"))["query"] == "校园活动有什么更新"


def test_guide_word_stripped_whole() -> None:
    """'帮我查一下X' 应整体剥离引导词，而非只去 '帮我查' 残留 '一下X'。"""
    assert _extract_utility_params("web_search", _state("帮我查一下明天天气"))["query"] == "明天天气"


def test_anaphora_reuses_last_query() -> None:
    r = _extract_utility_params("web_search", _state("再查一下", "校园活动有什么更新"))
    assert r["query"] == "校园活动有什么更新"
    assert r.get("reused_last") is True


def test_anaphora_without_history_falls_back_to_message() -> None:
    r = _extract_utility_params("web_search", _state("再查一下"))
    assert r["query"] == "再查一下"
    assert r.get("reused_last") is not True


def test_anaphora_variants() -> None:
    for msg in ("再搜一次", "继续", "查查", "还有呢", "接着查"):
        r = _extract_utility_params("web_search", _state(msg, "社团招新最新消息"))
        assert r["query"] == "社团招新最新消息", msg
        assert r.get("reused_last") is True, msg


def test_search_policy_anaphora() -> None:
    r = _extract_utility_params("search_policy", _state("继续", "NUS 奖学金政策"))
    assert r["query"] == "NUS 奖学金政策"
    assert r.get("reused_last") is True
