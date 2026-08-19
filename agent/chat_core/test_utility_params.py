"""测试 — Utility 工具参数提取。

覆盖两类：
1. 搜索词提取与回指复用："再查一下/继续"等回指请求复用上一次实际查询词
   （state.last_search_query），修复回指请求被当作字面查询词搜索的问题。
2. unit_converter 参数解析（全 LLM，2026-08-19）：主流货币、词表外货币
   （越南盾）、回指继承、长度/重量/温度、无法解析降级。规则词表已废弃。
"""

from langchain_core.messages import HumanMessage

from orchestration.graph.nodes import _extract_utility_params


def _state(msg: str, last_search_query: str | None = None) -> dict:
    return {"messages": [HumanMessage(content=msg)], "last_search_query": last_search_query}


# ─── 搜索词提取与回指复用 ───


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


# ─── unit_converter 参数解析（全 LLM）───


class _FakeLLM:
    """返回固定 content 的假 LLM（ainvoke 异步）。"""

    def __init__(self, content: str) -> None:
        self._content = content

    async def ainvoke(self, messages: list) -> object:  # noqa: ARG002
        class _Response:
            content = self._content

        return _Response()


def _patch_llm(monkeypatch, content: str) -> None:
    from orchestration.graph import nodes

    def fake_chat_llm() -> _FakeLLM:
        return _FakeLLM(content)

    monkeypatch.setattr(nodes, "chat_llm", fake_chat_llm)


async def test_llm_parse_mainstream_currency(monkeypatch) -> None:
    """主流货币对（美元→人民币）由 LLM 解析为 ISO 码。"""
    from orchestration.graph import nodes

    _patch_llm(monkeypatch, '{"value": 100, "from_unit": "USD", "to_unit": "CNY"}')
    r = await nodes._llm_extract_unit_converter({"messages": [HumanMessage(content="100美元是多少人民币")]})
    assert r == {"value": 100.0, "from_unit": "USD", "to_unit": "CNY"}


async def test_llm_parse_unknown_currency(monkeypatch) -> None:
    """词表外货币（越南盾）→ LLM 解析为 ISO 码。"""
    from orchestration.graph import nodes

    _patch_llm(monkeypatch, '{"value": 100, "from_unit": "SGD", "to_unit": "VND"}')
    r = await nodes._llm_extract_unit_converter({"messages": [HumanMessage(content="100新币是多少越南盾")]})
    assert r == {"value": 100.0, "from_unit": "SGD", "to_unit": "VND"}


async def test_llm_parse_anaphora(monkeypatch) -> None:
    """回指（'是多少美元'）→ LLM 结合历史继承金额与基准币，只更新目标币。"""
    from orchestration.graph import nodes

    _patch_llm(monkeypatch, '{"value": 100, "from_unit": "SGD", "to_unit": "USD"}')
    state = {
        "messages": [
            HumanMessage(content="100新币是多少人民币"),
            HumanMessage(content="是多少美元"),
        ]
    }
    r = await nodes._llm_extract_unit_converter(state)
    assert r == {"value": 100.0, "from_unit": "SGD", "to_unit": "USD"}


async def test_llm_parse_temperature(monkeypatch) -> None:
    """长度/重量/温度 → LLM 输出中文单位词。"""
    from orchestration.graph import nodes

    _patch_llm(monkeypatch, '{"value": 100, "from_unit": "摄氏度", "to_unit": "华氏度"}')
    r = await nodes._llm_extract_unit_converter({"messages": [HumanMessage(content="100摄氏度换成华氏度")]})
    assert r == {"value": 100.0, "from_unit": "摄氏度", "to_unit": "华氏度"}


async def test_llm_unparseable(monkeypatch) -> None:
    """LLM 无法解析（返回 error）→ None，维持原失败路径。"""
    from orchestration.graph import nodes

    _patch_llm(monkeypatch, '{"error": "无法解析"}')
    r = await nodes._llm_extract_unit_converter({"messages": [HumanMessage(content="随便聊聊")]})
    assert r is None


async def test_rule_fallback_when_llm_fails(monkeypatch) -> None:
    """LLM 无法解析时，规则兜底仍可提取主流货币对（如 100美元→人民币）。"""
    from orchestration.graph import nodes

    _patch_llm(monkeypatch, '{"error": "无法解析"}')
    state = {"messages": [HumanMessage(content="100美元是多少人民币")]}
    llm_r = await nodes._llm_extract_unit_converter(state)
    assert llm_r is None
    rule_r = nodes._rule_extract_unit_converter("100美元是多少人民币")
    assert rule_r == {"value": 100.0, "from_unit": "美元", "to_unit": "人民币"}
