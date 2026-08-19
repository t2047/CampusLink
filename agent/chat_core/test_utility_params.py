"""测试 — Utility 工具 unit_converter 参数提取（_extract_unit_converter_params）。

覆盖：货币对提取（中文/ISO）、反向顺序、小数、温度、缺单位回退。
修复：此前 _extract_utility_params 无 unit_converter 分支，params 为空导致
MCP 必填参数缺失、工具失败显示"货币换算服务暂时不可用"。
"""

from langchain_core.messages import HumanMessage

from orchestration.graph.nodes import _extract_utility_params


def _state(msg: str) -> dict:
    return {"messages": [HumanMessage(content=msg)]}


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
