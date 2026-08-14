import os
from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient

os.environ.setdefault("AGENT_SHARED_SECRET", "a" * 64)
os.environ.setdefault("AGENT_BACKEND_SHARED_SECRET", "b" * 64)
os.environ.setdefault("LOST_FOUND_CONFIRMATION_SECRET", "c" * 64)

from lost_found_agent.config import Settings  # noqa: E402
from lost_found_agent.main import create_app  # noqa: E402
from lost_found_agent.tools import (  # noqa: E402
    CampusApiClient,
    ClaimItemInput,
    GetItemDetailInput,
    ReportFoundInput,
    ReportLostInput,
    SearchFoundItemsInput,
    SearchLostItemsInput,
)


class FakeCampusApiClient(CampusApiClient):
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, object]] = []
        self.candidates: list[dict[str, object]] = []
        self.lost_candidates: list[dict[str, object]] = []

    async def close(self) -> None:
        return None

    async def report_lost(
        self, user_id: str, user_role: str, payload: ReportLostInput
    ) -> dict[str, object]:
        self.calls.append(("report_lost", user_id, payload))
        return {"id": 101, "reportType": "LOST", "itemName": payload.item_name}

    async def report_found(
        self, user_id: str, user_role: str, payload: ReportFoundInput
    ) -> dict[str, object]:
        self.calls.append(("report_found", user_id, payload))
        return {"id": 102, "reportType": "FOUND", "itemName": payload.item_name}

    async def search_found_items(
        self, user_id: str, user_role: str, payload: SearchFoundItemsInput
    ) -> dict[str, object]:
        self.calls.append(("search_found_items", user_id, payload))
        return {"content": self.candidates, "totalElements": len(self.candidates)}

    async def search_lost_items(
        self, user_id: str, user_role: str, payload: SearchLostItemsInput
    ) -> dict[str, object]:
        self.calls.append(("search_lost_items", user_id, payload))
        return {"content": self.lost_candidates, "totalElements": len(self.lost_candidates)}

    async def get_item_detail(
        self, user_id: str, user_role: str, payload: GetItemDetailInput
    ) -> dict[str, object]:
        self.calls.append(("get_item_detail", user_id, payload))
        return {
            "id": payload.report_id,
            "itemName": "Black headphones",
            "category": "ELECTRONICS",
            "description": "A black headset in a cloth case",
            "location": "Central Library",
            "eventDate": "2026-08-08",
            "status": "OPEN",
        }

    async def claim_item(
        self, user_id: str, user_role: str, payload: ClaimItemInput
    ) -> dict[str, object]:
        self.calls.append(("claim_item", user_id, payload))
        return {"id": 202, "status": "SUBMITTED"}


@pytest.fixture
def settings() -> Settings:
    return Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        agent_rate_limit_per_minute=20,
        agent_rate_limit_per_session=20,
        # 规则引擎测试必须锁定 rules 模式：auto 会依赖环境里的 LLM key，
        # 一旦 CI/本地注入了 LOST_FOUND_LLM_API_KEY 就变 llm 模式导致行为漂移
        lost_found_agent_mode="rules",
    )


@pytest.fixture
def fake_api() -> FakeCampusApiClient:
    return FakeCampusApiClient()


@pytest.fixture
def client(settings: Settings, fake_api: FakeCampusApiClient) -> Generator[TestClient, None, None]:
    with TestClient(create_app(settings, fake_api)) as test_client:
        yield test_client
