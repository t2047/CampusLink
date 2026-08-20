"""Settings 配置解析测试：验证 agent 运行模式的自动推导与配置约束。

覆盖的功能点：
- ``auto`` 模式在未配置 LLM API key 时自动回退为 rules（规则引擎）模式；
- ``llm`` 模式缺少 API key 时必须抛出 pydantic 校验错误，防止误配置；
- ``auto`` 模式配置了 API key 时自动启用 llm 模式。

被测模块：``lost_found_agent.config.Settings``（通过 ``effective_mode`` 属性暴露最终生效模式）。
测试策略：纯单元测试，不依赖任何 HTTP/mock fixture，直接构造 Settings 对象并断言其属性；
所有 secret 字段都使用 64 位重复字符，以满足配置校验对最小长度的要求。
"""

import pytest
from pydantic import ValidationError

from lost_found_agent.config import Settings


def test_auto_mode_without_api_key_uses_rules() -> None:
    """auto 模式 + 空 LLM API key：effective_mode 必须回退为 "rules"。

    这是默认兜底行为——即使部署时忘记配置模型密钥，
    Agent 也不应崩溃，而是退化为纯规则引擎继续服务。
    """
    settings = Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_agent_mode="auto",  # auto：让配置自行决定最终模式
        lost_found_llm_api_key="",  # 没有配置模型密钥
    )

    assert settings.effective_mode == "rules"


def test_llm_mode_requires_api_key() -> None:
    """llm 模式 + 空 API key：必须抛出 ValidationError 拒绝启动。

    与 auto 的"静默回退"不同，显式声明 llm 模式却又没有 key
    属于配置错误，应当在配置阶段就暴露出来，而不是运行时才失败。
    """
    # pytest.raises：断言构造 Settings 时抛出的异常类型为 pydantic.ValidationError
    with pytest.raises(ValidationError):
        Settings(
            agent_shared_secret="a" * 64,
            agent_backend_shared_secret="b" * 64,
            lost_found_confirmation_secret="c" * 64,
            lost_found_agent_mode="llm",  # 显式要求 LLM 模式
            lost_found_llm_api_key="",  # 却未提供密钥 → 校验失败
        )


def test_auto_mode_with_api_key_uses_llm() -> None:
    """auto 模式 + 已配置 API key：effective_mode 自动选择 "llm"。

    与第一个用例对照，构成 auto 模式两条分支的完整覆盖。
    """
    settings = Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_agent_mode="auto",  # auto：按配置自动决定
        lost_found_llm_api_key="configured",  # 有密钥 → 走 LLM
    )

    assert settings.effective_mode == "llm"
