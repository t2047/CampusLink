"""MCP 适配层异常兜底分支测试（chat-memory-requirements §9.13 / 待修复清单 P0-fix）。

``lost_found_server._invoke_tool`` 的异常兜底分支在 invoke service 抛异常时应返回
status=failed JSON（而不是把异常二次冒泡中断 MCP 响应流）。曾因异常分支调用
``detect_language`` 但未导入导致二次 ``NameError``（§0.2 P0-fix）。

覆盖：
- 中文消息 → 兜底返回中文"内部错误"文案，status=failed；
- 英文消息 → 兜底返回英文文案（detect_language 分支覆盖）。
"""

import importlib
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

from lost_found_agent.config import Settings

# CI 以 agent/lost_found_agent 为 rootdir（testpaths=["tests"]），agent/ 不在 sys.path；
# 与 mcp_servers/tests/test_utility_server.py 一样，把 agent/ 加入路径使 mcp_servers 可导入。
_AGENT_ROOT = Path(__file__).resolve().parents[2]
if str(_AGENT_ROOT) not in sys.path:
    sys.path.insert(0, str(_AGENT_ROOT))

# 导入 mcp_servers.lost_found_server 会在模块顶层执行 load_dotenv(find_dotenv())，
# 把仓库根 .env（含 LOST_FOUND_LLM_API_KEY 等）写入 os.environ。这些变量会经
# pydantic-settings 被后续测试的 Settings fixture 读取，导致 /health 的
# model_configured、classify 的 LLM 兜底行为漂移。因此 import 后立即恢复快照，
# 保持本测试对 os.environ 无副作用（测试内不依赖这些变量）。
_ENV_SNAPSHOT = dict(os.environ)
importlib.import_module("mcp_servers.lost_found_server")  # noqa: E402 仅触发模块顶层 load_dotenv
for _k in set(os.environ) - set(_ENV_SNAPSHOT):
    del os.environ[_k]
os.environ.update(_ENV_SNAPSHOT)

from mcp_servers.lost_found_server import (  # type: ignore[import-not-found]  # noqa: E402
    _invoke_tool,
)

from lost_found_agent.rate_limit import RateLimiter  # noqa: E402

_CLAIMS = {"sub": "42", "role": "STUDENT", "intended_action": "invoke"}


class _FailingInvokeService:
    """handle_invoke 恒抛异常，用于触发 _invoke_tool 的异常兜底分支。"""

    async def handle_invoke(self, *args: object, **kwargs: object) -> None:
        raise RuntimeError("simulated invoke failure")


def _fake_context() -> SimpleNamespace:
    """让 identity_from_context 可用的最小假 FastMCP Context（不会真正访问请求头）。"""
    return SimpleNamespace(request_context=SimpleNamespace(request=None))


async def _invoke(
    message: str,
    settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> dict[str, Any]:
    monkeypatch.setattr(
        "mcp_servers.lost_found_server.identity_from_context",
        lambda context, agent_name: _CLAIMS,
    )
    result = await _invoke_tool(
        message,
        {"session_id": "s1", "shared_data": {}},
        False,
        None,
        None,
        context=_fake_context(),
        settings=settings,
        limiter=RateLimiter(20, 20),
        invoke_service=_FailingInvokeService(),
    )
    return cast(dict[str, Any], json.loads(result))


@pytest.mark.asyncio
async def test_mcp_exception_fallback_zh(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    """中文消息 → 兜底返回 status=failed + 中文文案，不二次 NameError（P0-fix 回归）。"""
    data = await _invoke("帮我找一下我的红色书包", settings, monkeypatch)
    assert data["status"] == "failed"
    assert "内部错误" in data["response"]
    assert data["request_id"]


@pytest.mark.asyncio
async def test_mcp_exception_fallback_en(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    """英文消息 → detect_language 走 en 分支返回英文文案。"""
    data = await _invoke("find my backpack", settings, monkeypatch)
    assert data["status"] == "failed"
    assert "internal error" in data["response"]
