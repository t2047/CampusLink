"""测试 — ServiceRegistry 配置解析（_resolve_env 占位符）。

覆盖 ${ENV_VAR} 与 ${ENV_VAR:default} 两种语法（services.yaml 注释宣称支持）：
环境变量优先、未设置用默认值、显式空串保留、复合字符串替换。
"""

from __future__ import annotations

from orchestration.mcp.registry import _resolve_env


def test_plain_placeholder(monkeypatch):
    monkeypatch.setenv("FOO", "bar")
    assert _resolve_env("${FOO}") == "bar"


def test_placeholder_with_default_when_unset(monkeypatch):
    monkeypatch.delenv("NOT_SET_URL", raising=False)
    assert _resolve_env("${NOT_SET_URL:http://localhost:8080}") == "http://localhost:8080"


def test_env_prefers_env_over_default(monkeypatch):
    monkeypatch.setenv("PORT", "9000")
    assert _resolve_env("${PORT:8080}") == "9000"


def test_empty_env_value_is_kept(monkeypatch):
    """显式设为空串时保留空（不回退到 default）。"""
    monkeypatch.setenv("EMPTY", "")
    assert _resolve_env("${EMPTY:fallback}") == ""


def test_placeholder_within_larger_string(monkeypatch):
    monkeypatch.delenv("HOST", raising=False)
    monkeypatch.delenv("PORT", raising=False)
    assert _resolve_env("http://${HOST:localhost}:${PORT:8080}") == "http://localhost:8080"
