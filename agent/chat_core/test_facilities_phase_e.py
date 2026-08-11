"""Phase E regression tests for Chat Core -> Facilities Domain Agent integration."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import httpx
import pytest
from langchain_core.messages import AIMessage, HumanMessage

from orchestration.graph.nodes import (
    AGENT_CAPABILITIES,
    _build_conversation_context,
    agent_invoker,
    human_approval,
    intent_router,
)
from orchestration.mcp.client import AgentClient
from orchestration.mcp.registry import AgentConfig, ServiceRegistry
from orchestration.streaming.sse_handler import structural_events_from_update

FACILITY_MESSAGES = [
    "Find a study room for 4 people",
    "Is room 3 available tomorrow afternoon?",
    "Book room 3 tomorrow from 2 to 4",
    "What is the status of booking 123?",
    "Cancel booking 123",
    "The projector in Engineering Block is broken",
    "What is the status of maintenance ticket 123?",
    "Show my bookings",
    "Show my maintenance requests",
    "帮我找一个适合4个人学习的房间",
    "3号房明天下午有空吗",
    "帮我预约3号房明天下午2点到4点",
    "我的预约123是什么状态",
    "取消预约123",
    "工程楼三楼投影仪坏了",
    "报修单123处理好了吗",
]

NON_FACILITY_ROUTES = [
    ("I lost my wallet", "domain_agent", ["lost-found-agent"]),
    ("Check my email", "domain_agent", ["mail-agent"]),
    ("What meetings do I have tomorrow?", "chat", []),
]


class FakeLLM:
    def __init__(self, intent_type: str, targets: list[str]):
        self.intent_type = intent_type
        self.targets = targets
        self.messages: list[Any] = []

    def invoke(self, messages):
        self.messages = messages
        return AIMessage(
            content=json.dumps(
                {
                    "intent_type": self.intent_type,
                    "targets": self.targets,
                    "reasoning": "unit-test route",
                }
            )
        )


def _router_state(message: str) -> dict[str, Any]:
    return {
        "messages": [HumanMessage(content=message)],
        "agent_plan": [],
        "utility_plan": [],
    }


@pytest.mark.parametrize("message", FACILITY_MESSAGES)
def test_facilities_messages_route_to_facility_agent(monkeypatch, message):
    fake = FakeLLM("domain_agent", ["facility-agent"])
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)

    result = intent_router(_router_state(message))

    assert result["intent_type"] == "domain_agent"
    assert result["agent_plan"] == ["facility-agent"]
    assert "facility-agent" in fake.messages[0].content


@pytest.mark.parametrize("message,intent_type,targets", NON_FACILITY_ROUTES)
def test_non_facilities_messages_do_not_route_to_facility_agent(monkeypatch, message, intent_type, targets):
    fake = FakeLLM(intent_type, targets)
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)

    result = intent_router(_router_state(message))

    assert "facility-agent" not in result["agent_plan"]


def test_facilities_capability_describes_full_read_write_lifecycle():
    capability = AGENT_CAPABILITIES["facility-agent"]
    for phrase in ("搜索", "详情", "可用", "创建", "查询", "列出", "取消", "预约", "报修"):
        assert phrase in capability
    assert "失物" not in capability
    assert "邮件" not in capability


def test_lost_found_capability_includes_found_item_registration():
    capability = AGENT_CAPABILITIES["lost-found-agent"]
    for phrase in ("报失", "登记拾获", "查找", "认领"):
        assert phrase in capability


def test_router_rejects_hallucinated_agent_target(monkeypatch):
    fake = FakeLLM("domain_agent", ["facility-agent", "unknown-agent"])
    monkeypatch.setattr("orchestration.graph.nodes.intent_llm", lambda: fake)

    result = intent_router(_router_state("Find a room"))

    assert result["agent_plan"] == ["facility-agent"]


def _facility_registry() -> ServiceRegistry:
    registry = ServiceRegistry(
        token_service_url="http://chat-backend:8080",
        shared_secret="unit-test-shared-secret",
    )
    registry.agents["facility-agent"] = AgentConfig(
        name="facility-agent",
        url="http://facility-agent-mcp:8082",
        mcp_url="http://facility-agent-mcp:8082/mcp/",
    )
    return registry


def test_facility_registry_uses_expected_name_url_and_audience(monkeypatch):
    monkeypatch.delenv("FACILITY_AGENT_MCP_URL", raising=False)
    config = Path(__file__).parent / "config" / "services.yaml"

    registry = ServiceRegistry.from_yaml(str(config))
    facility = registry.get_agent("facility-agent")

    assert facility is not None
    assert facility.mcp_url == "http://127.0.0.1:8082/mcp/"
    assert facility.name == "facility-agent"


def test_facility_token_exchange_sets_audience_and_intended_action():
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"token": "delegation-token"})

    async def scenario():
        client = AgentClient(
            registry=_facility_registry(),
            _http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        )
        try:
            return await client.get_delegation_token("42", "STUDENT", "facility-agent")
        finally:
            await client.close()

    assert asyncio.run(scenario()) == "delegation-token"
    assert captured["body"]["targetAgent"] == "facility-agent"
    assert captured["body"]["intendedAction"] == "invoke"


def test_agent_client_sends_exact_five_field_invoke_contract(monkeypatch):
    captured: dict[str, Any] = {}

    async def scenario():
        client = AgentClient(registry=_facility_registry())

        async def obtain(user_id, role, target_agent, jti=None):
            return "delegation-token"

        async def call(mcp_url, tool_name, arguments, token):
            captured.update(
                url=mcp_url,
                tool=tool_name,
                arguments=arguments,
                token=token,
            )
            return {
                "response": "ok",
                "status": "completed",
                "request_id": "facility-request-1",
            }

        monkeypatch.setattr(client, "_obtain_delegation_token", obtain)
        monkeypatch.setattr(client, "_call_mcp_tool", call)
        try:
            return await client.invoke_agent(
                agent_name="facility-agent",
                message="Show my bookings",
                user_id="42",
                user_role="STUDENT",
                conversation_context={"session_id": "thread-1", "shared_data": {}},
                trace_id="trace-1",
            )
        finally:
            await client.close()

    result = asyncio.run(scenario())
    assert captured["url"] == "http://facility-agent-mcp:8082/mcp/"
    assert captured["tool"] == "invoke"
    assert captured["token"] == "delegation-token"
    assert set(captured["arguments"]) == {
        "message",
        "conversation_context",
        "confirmed",
        "confirmation_id",
        "trace_parent",
    }
    assert captured["arguments"]["confirmation_id"] is None
    assert result["request_id"] == "facility-request-1"


def test_resume_obtains_a_fresh_delegation_token(monkeypatch):
    tokens = iter(["delegation-token-1", "delegation-token-2"])
    observed: list[tuple[str, bool, str | None]] = []

    async def scenario():
        client = AgentClient(registry=_facility_registry())

        async def obtain(user_id, role, target_agent, jti=None):
            return next(tokens)

        async def call(mcp_url, tool_name, arguments, token):
            observed.append((token, arguments["confirmed"], arguments["confirmation_id"]))
            return {"response": "ok", "status": "completed"}

        monkeypatch.setattr(client, "_obtain_delegation_token", obtain)
        monkeypatch.setattr(client, "_call_mcp_tool", call)
        try:
            await client.invoke_agent("facility-agent", "Book room 3", "42", "STUDENT")
            await client.invoke_agent(
                "facility-agent",
                "Book room 3",
                "42",
                "STUDENT",
                confirmed=True,
                confirmation_id="confirm-1",
            )
        finally:
            await client.close()

    asyncio.run(scenario())
    assert observed == [
        ("delegation-token-1", False, None),
        ("delegation-token-2", True, "confirm-1"),
    ]


class FakeAgentClient:
    def __init__(self, results: list[dict[str, Any]]):
        self.results = iter(results)
        self.calls: list[dict[str, Any]] = []

    async def invoke_agent(self, **kwargs):
        self.calls.append(kwargs)
        return next(self.results)


def _agent_state(**overrides) -> dict[str, Any]:
    state: dict[str, Any] = {
        "messages": [HumanMessage(content="Book room 3 tomorrow from 2 to 4")],
        "agent_plan": ["facility-agent"],
        "current_agent_index": 0,
        "user_id": "42",
        "user_role": "STUDENT",
        "trace_id": "trace-1",
        "session_id": "stable-thread",
        "conversation_context": {"shared_data": {"mail": {"message_id": "m1"}}},
        "agent_invocations": [],
        "failed_agents": [],
        "service_failures": [],
    }
    state.update(overrides)
    return state


def test_conversation_context_keeps_session_and_domain_namespaces():
    context = _build_conversation_context(
        _agent_state(
            agent_invocations=[
                {"shared_context": {"facilities": {"last_booking_id": 123}}},
                {"shared_context": {"lost_found": {"last_item_id": 456}}},
            ]
        )
    )

    assert context["session_id"] == "stable-thread"
    shared = context["shared_data"]
    assert shared["mail"] == {"message_id": "m1"}
    assert shared["facilities"] == {"last_booking_id": 123}
    assert shared["lost_found"] == {"last_item_id": 456}
    # 编排层统一注入的系统事实包（日期格式 YYYY-MM-DD，不绑定具体日期）
    assert shared["system_facts"]["timezone"] == "Asia/Singapore"
    assert len(shared["system_facts"]["today"]) == 10
    assert isinstance(shared["recent_messages"], list)


@pytest.mark.parametrize(
    "action",
    ["create_booking", "cancel_booking", "submit_maintenance_request"],
)
def test_facilities_write_actions_enter_hitl(action):
    result = {
        "response": "Please confirm",
        "status": "needs_confirmation",
        "confirmation_required": {
            "confirmation_id": f"confirm-{action}",
            "action": action,
        },
        "shared_context": {"facilities": {"last_intent": action}},
        "actions_taken": [],
        "request_id": f"request-{action}",
        "error": None,
    }
    client = FakeAgentClient([result])

    update = asyncio.run(agent_invoker(_agent_state(), client=client))

    assert update["requires_approval"] is True
    assert update["approval_agent"] == "facility-agent"
    assert update["approval_context"]["confirmation_id"] == f"confirm-{action}"
    assert update["current_agent_index"] == 0
    assert update["agent_invocations"][-1]["request_id"] == f"request-{action}"
    assert client.calls[0]["conversation_context"]["session_id"] == "stable-thread"


def test_facilities_confirmation_resume_keeps_session_message_and_id():
    client = FakeAgentClient([{"response": "Booked", "status": "completed", "request_id": "request-2"}])
    state = _agent_state(
        pending_confirmation={
            "agent_name": "facility-agent",
            "confirmation_id": "confirm-create-booking",
        },
        agent_invocations=[
            {
                "agent_name": "facility-agent",
                "output_status": "confirmed",
                "shared_context": {"facilities": {"selected_space": {"spaceId": 3}}},
            }
        ],
    )

    update = asyncio.run(agent_invoker(state, client=client))

    call = client.calls[0]
    assert call["confirmed"] is True
    assert call["confirmation_id"] == "confirm-create-booking"
    assert call["message"] == "Book room 3 tomorrow from 2 to 4"
    assert call["conversation_context"]["session_id"] == "stable-thread"
    assert call["conversation_context"]["shared_data"]["facilities"] == {"selected_space": {"spaceId": 3}}
    assert update["current_agent_index"] == 1


def test_declined_confirmation_does_not_reinvoke_agent(monkeypatch):
    import langgraph.types

    monkeypatch.setattr(langgraph.types, "interrupt", lambda _payload: {"approved": False})
    state = _agent_state(
        requires_approval=True,
        approval_agent="facility-agent",
        approval_context={"confirmation_id": "confirm-1"},
        agent_invocations=[
            {
                "agent_name": "facility-agent",
                "output_status": "needs_confirmation",
                "output_response": "Please confirm",
            }
        ],
    )

    update = human_approval(state)

    assert update["agent_invocations"][-1]["output_status"] == "cancelled"
    assert update["agent_invocations"][-1]["output_response"] == "操作已取消。"
    assert update["current_agent_index"] == 1
    assert "pending_confirmation" not in update


@pytest.mark.parametrize("status", ["completed", "needs_more_info"])
def test_non_failure_facilities_statuses_are_not_marked_failed(status):
    client = FakeAgentClient([{"response": "result", "status": status, "error": None}])

    update = asyncio.run(agent_invoker(_agent_state(), client=client))

    assert "failed_agents" not in update
    assert "service_failures" not in update
    assert update["agent_invocations"][-1]["output_status"] == status


@pytest.mark.parametrize(
    "error_code",
    [
        "FACILITIES_PLANNER_NOT_CONFIGURED",
        "FACILITIES_PLANNER_TIMEOUT",
        "FACILITIES_BACKEND_ERROR",
        "FACILITIES_AUTHENTICATION_FAILED",
        "MCP service 'facility-agent' is unreachable",
    ],
)
def test_facilities_failures_remain_failed_without_leaking_error_to_sse(error_code):
    client = FakeAgentClient(
        [
            {
                "response": "The facilities service is temporarily unavailable.",
                "status": "failed",
                "request_id": "facility-request-failed",
                "error": error_code,
            }
        ]
    )

    update = asyncio.run(agent_invoker(_agent_state(), client=client))
    events = structural_events_from_update("agent_invoker", update)

    assert update["failed_agents"] == ["facility-agent"]
    assert update["agent_invocations"][-1]["output_status"] == "failed"
    assert any(event.event == "agent_error" for event in events)
    assert all(event.data.get("request_id") == "facility-request-failed" for event in events)
    assert all(error_code not in json.dumps(event.data) for event in events)
