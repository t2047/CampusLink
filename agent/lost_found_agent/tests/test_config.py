import pytest
from pydantic import ValidationError

from lost_found_agent.config import Settings


def test_auto_mode_without_api_key_uses_rules() -> None:
    settings = Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_agent_mode="auto",
        lost_found_llm_api_key="",
    )

    assert settings.effective_mode == "rules"


def test_llm_mode_requires_api_key() -> None:
    with pytest.raises(ValidationError):
        Settings(
            agent_shared_secret="a" * 64,
            agent_backend_shared_secret="b" * 64,
            lost_found_confirmation_secret="c" * 64,
            lost_found_agent_mode="llm",
            lost_found_llm_api_key="",
        )


def test_auto_mode_with_api_key_uses_llm() -> None:
    settings = Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_agent_mode="auto",
        lost_found_llm_api_key="configured",
    )

    assert settings.effective_mode == "llm"
