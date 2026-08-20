"""pytest 共享夹具（conftest）。

为整个 tests 包提供：
- 在导入阶段用 os.environ.setdefault 预置 3 个安全密钥环境变量，
  保证 Settings 初始化不缺字段；
- settings：一份锁定"规则引擎模式"的测试配置（lost_found_agent_mode="rules"），
  避免因环境注入 LLM key 导致行为漂移到 llm 模式；
- fake_api：不访问真实后端的假 CampusApiClient，逐条记录调用；
- client：FastAPI TestClient，供所有 HTTP 集成测试共用。

此外还定义了 FakeCampusApiClient——一个把各写/查动作记录到 self.calls 的
桩实现，让测试能断言 Agent 调用了哪些后端接口。
"""

import os
from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient

# 在导入任何模块前先预置安全密钥，确保 Settings 校验通过（模块级副作用）
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
    """假的后端客户端：继承真实接口但不发起任何网络请求。

    每个方法都把 (方法名, user_id, payload) 追加到 self.calls，
    并返回固定 / 可配置的响应；candidates / lost_candidates 可由测试预置，
    用于模拟搜索结果。
    """

    def __init__(self) -> None:
        self.calls: list[tuple[str, str, object]] = []  # 记录每次调用的 (动作, 用户, 载荷)
        self.candidates: list[dict[str, object]] = []  # 预置"拾获物品"搜索结果
        self.lost_candidates: list[dict[str, object]] = []  # 预置"丢失物品"搜索结果

    async def close(self) -> None:
        return None  # 假客户端无需释放底层资源

    async def report_lost(
        self, user_id: str, user_role: str, payload: ReportLostInput
    ) -> dict[str, object]:
        self.calls.append(("report_lost", user_id, payload))  # 记录上报丢失动作
        return {"id": 101, "reportType": "LOST", "itemName": payload.item_name}  # 固定返回新建的报失 id

    async def report_found(
        self, user_id: str, user_role: str, payload: ReportFoundInput
    ) -> dict[str, object]:
        self.calls.append(("report_found", user_id, payload))  # 记录上报拾获动作
        return {"id": 102, "reportType": "FOUND", "itemName": payload.item_name}  # 固定返回新建的拾获 id

    async def search_found_items(
        self, user_id: str, user_role: str, payload: SearchFoundItemsInput
    ) -> dict[str, object]:
        self.calls.append(("search_found_items", user_id, payload))  # 记录搜索拾获动作
        return {"content": self.candidates, "totalElements": len(self.candidates)}  # 返回预置候选与总数

    async def search_lost_items(
        self, user_id: str, user_role: str, payload: SearchLostItemsInput
    ) -> dict[str, object]:
        self.calls.append(("search_lost_items", user_id, payload))  # 记录搜索丢失动作
        return {"content": self.lost_candidates, "totalElements": len(self.lost_candidates)}  # 返回预置候选与总数

    async def get_item_detail(
        self, user_id: str, user_role: str, payload: GetItemDetailInput
    ) -> dict[str, object]:
        self.calls.append(("get_item_detail", user_id, payload))  # 记录详情查询动作
        # 返回一条固定的"黑色耳机"详情，供详情类测试断言
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
        self.calls.append(("claim_item", user_id, payload))  # 记录认领动作
        return {"id": 202, "status": "SUBMITTED"}  # 固定返回"已提交认领"状态


@pytest.fixture
def settings() -> Settings:
    """一份测试专用配置：锁定规则引擎模式并抬高限流上限。

    @pytest.fixture：pytest 夹具，测试函数按参数名自动注入该返回值。
    """
    return Settings(
        agent_shared_secret="a" * 64,
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        agent_rate_limit_per_minute=20,  # 每用户每分钟 20 次，避免测试误触限流
        agent_rate_limit_per_session=20,  # 每会话 20 次
        # 规则引擎测试必须锁定 rules 模式：auto 会依赖环境里的 LLM key，
        # 一旦 CI/本地注入了 LOST_FOUND_LLM_API_KEY 就变 llm 模式导致行为漂移
        lost_found_agent_mode="rules",
    )


@pytest.fixture
def fake_api() -> FakeCampusApiClient:
    """返回一个空的假后端客户端，供各测试注入候选数据或读取 calls 记录。"""
    return FakeCampusApiClient()


@pytest.fixture
def client(settings: Settings, fake_api: FakeCampusApiClient) -> Generator[TestClient, None, None]:
    """用测试配置 + 假客户端构建 FastAPI 应用并包装为 TestClient。

    TestClient 提供同步调用接口，with 块负责请求生命周期（startup/shutdown）；
    依赖 settings 与 fake_api 两个夹具，二者由 pytest 自动解析注入。
    """
    with TestClient(create_app(settings, fake_api)) as test_client:
        yield test_client  # 把 TestClient 交给测试函数，用后自动关闭
