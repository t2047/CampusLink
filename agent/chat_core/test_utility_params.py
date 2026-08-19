"""测试 — Utility 工具参数提取（_extract_utility_params）。

覆盖两类：
1. 搜索词提取与回指复用："再查一下/继续"等回指请求复用上一次实际查询词
   （state.last_search_query），修复回指请求被当作字面查询词搜索的问题。
2. unit_converter 参数提取：货币对（中文/ISO）、反向顺序、小数、温度、
   缺单位回退。修复此前无该分支导致 params 为空、MCP 必填参数缺失、
   工具失败显示"货币换算服务暂时不可用"。
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


# ─── unit_converter 参数提取 ───


def test_unit_converter_extracts_currency_pair() -> None:
    r = _extract_utility_params("unit_converter", _state("100美元是多少人民币"))
    assert r == {"value": 100.0, "from_unit": "美元", "to_unit": "人民币"}


def test_unit_converter_reverse_order() -> None:
    r = _extract_utility_params("unit_converter", _state("100人民币等于多少美元"))
    assert r["from_unit"] == "人民币"
    assert r["to_unit"] == "美元"


def test_unit_converter_decimal_and_iso() -> None:
    r = _extract_utility_params("unit_converter", _state("把15.5 USD 换算成 CNY"))
    assert r["value"] == 15.5
    assert r["from_unit"] == "USD"
    assert r["to_unit"] == "CNY"


def test_unit_converter_temperature() -> None:
    r = _extract_utility_params("unit_converter", _state("100摄氏度换成华氏度"))
    assert r["from_unit"] == "摄氏度"
    assert r["to_unit"] == "华氏度"


def test_unit_converter_missing_units_returns_empty() -> None:
    assert _extract_utility_params("unit_converter", _state("100美元多少钱")) == {}
    assert _extract_utility_params("unit_converter", _state("多少钱")) == {}
