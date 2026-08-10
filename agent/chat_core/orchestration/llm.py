"""LLM 工厂 — Chat Core 编排层统一模型入口。

默认使用 DeepSeek（.env 中 DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL）。
所有 LLM 调用（意图分类 / 闲聊 / 聚合）均通过本工厂创建，保证模型配置单一来源，
便于切换与测试注入 mock。

注意：所有模型开启 ``streaming=True``。
- 让 ``langgraph.astream(stream_mode="messages")`` 能捕获 token 级增量，
  从而在前端实现真正的打字机效果；
- 首个 token 到达更快（不必等完整回复）。
"""

from __future__ import annotations

import os
from functools import lru_cache
from typing import Optional

from langchain_openai import ChatOpenAI

DEFAULT_BASE_URL = "https://api.deepseek.com"
DEFAULT_MODEL = "deepseek-v4-flash"


def _env(key: str, default: str) -> str:
    return os.environ.get(key, "").strip() or default


@lru_cache(maxsize=1)
def get_llm(
    temperature: float = 0.0,
    max_tokens: Optional[int] = None,
    model: Optional[str] = None,
) -> ChatOpenAI:
    """创建 LLM 实例（进程内缓存）。streaming=True 支持 token 级流式。"""
    return ChatOpenAI(
        model=model or _env("DEEPSEEK_MODEL", DEFAULT_MODEL),
        api_key=_env("DEEPSEEK_API_KEY", ""),
        base_url=_env("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL),
        temperature=temperature,
        max_tokens=max_tokens,
        timeout=30,
        streaming=True,
    )


def intent_llm() -> ChatOpenAI:
    """意图分类专用：temperature=0 保证确定性。"""
    return get_llm(temperature=0.0)


def chat_llm() -> ChatOpenAI:
    """闲聊/生成专用：temperature=0.7。"""
    return get_llm(temperature=0.7)


def summary_llm() -> ChatOpenAI:
    """聚合专用：temperature=0.3。"""
    return get_llm(temperature=0.3)
