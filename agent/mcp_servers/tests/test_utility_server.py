"""测试 — utility_server 单位换算(实时汇率/回退/别名/错误语义)。"""

import json
import sys
from pathlib import Path

# 使 mcp_servers 包可导入（直接运行/CI 均可用）
_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from mcp_servers.utility_server import (
    _to_iso_code,
    unit_converter,
)


def test_to_iso_code_supports_alias_and_iso() -> None:
    assert _to_iso_code("人民币") == "CNY"
    assert _to_iso_code("美元") == "USD"
    assert _to_iso_code("USD") == "USD"
    assert _to_iso_code("cny") == "CNY"  # 大小写归一化
    assert _to_iso_code("米") is None  # 非货币
    assert _to_iso_code("US") is None  # 非 3 字母


def test_same_currency_identity() -> None:
    r = json.loads(unit_converter(100, "人民币", "人民币"))
    assert r["result"] == 100 and r["rate"] == 1.0 and r["source"] == "identity"
    r = json.loads(unit_converter(100, "USD", "usd"))
    assert r["source"] == "identity"


def test_iso_code_input() -> None:
    r = json.loads(unit_converter(100, "USD", "CNY"))
    assert "error" not in r and r["result"] > 0


def test_chinese_alias_input() -> None:
    r = json.loads(unit_converter(100, "美元", "人民币"))
    assert "error" not in r and r["result"] > 0


def test_mixed_currency_error_semantics() -> None:
    # 一侧货币一侧非货币 → 明确的货币错误（而非落入非货币分支）
    r = json.loads(unit_converter(100, "人民币", "瑞士法郎"))
    assert "unsupported currency" in r["error"]
    r = json.loads(unit_converter(100, "米", "USD"))
    assert "unsupported currency" in r["error"]


def test_non_currency_unaffected() -> None:
    r = json.loads(unit_converter(1, "米", "公里"))
    assert r["result"] == 0.001
    r = json.loads(unit_converter(100, "摄氏度", "华氏度"))
    assert r["result"] == 212


def test_fallback_when_rates_unavailable(monkeypatch) -> None:
    """汇率 API 失败 → 回退固定汇率（source=fallback）。"""
    monkeypatch.setattr("mcp_servers.utility_server._get_rates", lambda _base: None)
    r = json.loads(unit_converter(100, "美元", "人民币"))
    assert r["source"] == "fallback"
    assert abs(r["result"] - 720) < 1


def test_unsupported_pair_returns_error(monkeypatch) -> None:
    """API 失败且无固定回退 → 错误。"""
    monkeypatch.setattr("mcp_servers.utility_server._get_rates", lambda _base: None)
    r = json.loads(unit_converter(100, "美元", "日元"))
    assert "error" in r
