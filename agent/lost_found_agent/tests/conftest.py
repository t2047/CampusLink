import os
from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient

os.environ.setdefault("AGENT_SHARED_SECRET", "a" * 64)
os.environ.setdefault("AGENT_BACKEND_SHARED_SECRET", "b" * 64)
os.environ.setdefault("LOST_FOUND_CONFIRMATION_SECRET", "c" * 64)

from lost_found_agent.config import Settings  # noqa: E402
from lost_found_agent.main import create_app  # noqa: E402


@pytest.fixture
def settings() -> Settings:
    return Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        agent_rate_limit_per_minute=20,
        agent_rate_limit_per_session=20,
    )


@pytest.fixture
def client(settings: Settings) -> Generator[TestClient, None, None]:
    with TestClient(create_app(settings)) as test_client:
        yield test_client
