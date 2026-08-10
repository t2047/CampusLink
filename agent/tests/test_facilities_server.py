from __future__ import annotations

import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

_AGENT_ROOT = Path(__file__).resolve().parents[1]
if str(_AGENT_ROOT) not in sys.path:
    sys.path.insert(0, str(_AGENT_ROOT))

from facilities_agent.confirmation import ConfirmationStore
from facilities_agent.models import InvokeResponse
from facilities_agent.planner import FakePlanner, PlannerDecision
from facilities_agent.tool_client import (
    FACILITIES_TOOL_NAMES,
    ToolClientAuthenticationError,
    ToolClientTransportError,
)
from mcp_servers import facilities_server, security

INBOUND_TOKEN = "inbound-delegation-token"


def valid_claims(subject="1"):
    return {
        "sub": subject,
        "role": "STUDENT",
        "aud": ["facility-agent"],
        "iss": "token-service",
        "iat": 1,
        "exp": 9999999999,
        "intended_action": "invoke",
        "jti": "test-jti",
    }


def fake_context(token=INBOUND_TOKEN):
    request = SimpleNamespace(headers={"Authorization": f"Bearer {token}"})
    request_context = SimpleNamespace(request=request)
    return SimpleNamespace(request_context=request_context)


class RecordingToolClient:
    def __init__(self, token, fixtures=None):
        self.token = token
        self.fixtures = {
            name: list(value) if isinstance(value, list) else [value]
            for name, value in (fixtures or {}).items()
        }
        self.calls = []
        self.enter_count = 0
        self.close_count = 0

    def __repr__(self):
        return "RecordingToolClient(state=test)"

    async def __aenter__(self):
        self.enter_count += 1
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        self.close_count += 1
        return False

    async def call_tool(self, name, arguments):
        self.calls.append((name, dict(arguments)))
        configured = self.fixtures.get(name, [])
        if not configured:
            return {"success": True, "data": None, "error": None}
        value = configured.pop(0) if len(configured) > 1 else configured[0]
        if isinstance(value, Exception):
            raise value
        return value


class RecordingToolClientFactory:
    def __init__(self, fixture_sets=None):
        self.fixture_sets = list(fixture_sets or [{}])
        self.clients = []
        self.tokens = []

    def __call__(self, token):
        index = len(self.clients)
        fixtures = self.fixture_sets[min(index, len(self.fixture_sets) - 1)]
        client = RecordingToolClient(token, fixtures)
        self.tokens.append(token)
        self.clients.append(client)
        return client


def planner_factory(decision):
    def factory(request):
        return FakePlanner({request.message: decision})

    return factory


async def invoke_adapter(
    message,
    *,
    token=INBOUND_TOKEN,
    session_id="session-1",
    shared_data=None,
    confirmed=False,
    confirmation_id=None,
):
    raw = await facilities_server._invoke_adapter(
        message=message,
        conversation_context={
            "session_id": session_id,
            "shared_data": shared_data or {},
        },
        confirmed=confirmed,
        confirmation_id=confirmation_id,
        trace_parent={"trace_id": "trace-1", "parent_span_id": "span-1"},
        context=fake_context(token),
    )
    return json.loads(raw), raw


@pytest.fixture(autouse=True)
def isolated_server_runtime(monkeypatch):
    security._VERIFIERS.clear()

    def verify(_self, token):
        subject = "2" if token == "other-user-token" else "1"
        return valid_claims(subject)

    monkeypatch.setattr(security.TokenVerifier, "verify_token", verify)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        facilities_server._default_planner_factory,
    )
    monkeypatch.setattr(
        facilities_server,
        "_service_factory",
        facilities_server._default_service_factory,
    )
    monkeypatch.setattr(facilities_server, "_confirmation_store", ConfirmationStore())
    yield
    security._VERIFIERS.clear()


@pytest.mark.asyncio
async def test_health_initialize_tools_list_and_invoke_schema(monkeypatch):
    monkeypatch.setattr(
        security.TokenVerifier,
        "verify_token",
        lambda _self, _token: valid_claims(),
    )
    security._VERIFIERS.clear()

    async with facilities_server.app.router.lifespan_context(facilities_server.app):
        transport = httpx.ASGITransport(app=facilities_server.app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://127.0.0.1:8082",
        ) as anonymous:
            health = await anonymous.get("/health")
            assert health.status_code == 200
            assert health.json() == {
                "status": "ok",
                "service": "facility-agent",
                "mcp": True,
            }

            missing = await anonymous.get("/mcp/")
            assert missing.status_code == 401

        async with (
            httpx.AsyncClient(
                transport=transport,
                base_url="http://127.0.0.1:8082",
                headers={"Authorization": f"Bearer {INBOUND_TOKEN}"},
            ) as authenticated,
            streamable_http_client(
                "http://127.0.0.1:8082/mcp/", http_client=authenticated
            ) as (read_stream, write_stream, _),
            ClientSession(read_stream, write_stream) as session,
        ):
            initialized = await session.initialize()
            tools = await session.list_tools()

    assert initialized.serverInfo.name == "facility-agent-server"
    assert [tool.name for tool in tools.tools] == ["invoke"]
    schema = tools.tools[0].inputSchema
    assert set(schema["properties"]) == {
        "message",
        "conversation_context",
        "confirmed",
        "confirmation_id",
        "trace_parent",
    }
    assert schema["required"] == ["message"]
    assert "userId" not in schema["properties"]
    assert "role" not in schema["properties"]
    assert "token" not in schema["properties"]


@pytest.mark.asyncio
async def test_inbound_authentication_failure_returns_failed_response(monkeypatch):
    def reject(_self, _token):
        raise ValueError("invalid token")

    monkeypatch.setattr(security.TokenVerifier, "verify_token", reject)
    security._VERIFIERS.clear()

    result, _ = await invoke_adapter("search spaces")

    assert result["status"] == "failed"
    assert result["error"] == "FACILITIES_AUTHENTICATION_FAILED"


@pytest.mark.asyncio
async def test_same_inbound_token_is_propagated_without_entering_arguments(
    monkeypatch,
):
    factory = RecordingToolClientFactory(
        [
            {
                "search_spaces": {
                    "success": True,
                    "data": [],
                    "error": None,
                }
            }
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(PlannerDecision(intent="search_spaces", arguments={})),
    )

    result, raw = await invoke_adapter("find a room")

    assert result["status"] == "completed"
    assert factory.tokens == [INBOUND_TOKEN]
    assert factory.clients[0].calls == [("search_spaces", {})]
    assert INBOUND_TOKEN not in raw
    assert INBOUND_TOKEN not in repr(factory.clients[0])


@pytest.mark.asyncio
async def test_search_completes_through_adapter(monkeypatch):
    factory = RecordingToolClientFactory(
        [
            {
                "search_spaces": {
                    "success": True,
                    "data": [
                        {
                            "spaceId": 4,
                            "name": "COM2 Project Room",
                            "building": "COM2",
                            "roomNumber": "03-01",
                            "spaceType": "STUDY_ROOM",
                            "capacity": 6,
                            "equipment": [],
                        }
                    ],
                    "error": None,
                }
            }
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(PlannerDecision(intent="search_spaces", arguments={})),
    )

    result, _ = await invoke_adapter("find a room")

    assert result["status"] == "completed"
    assert "1 matching space" in result["response"]
    assert result["actions_taken"][0]["action"] == "search_spaces"


@pytest.mark.asyncio
async def test_unknown_transport_planner_input_needs_more_info(monkeypatch):
    factory = RecordingToolClientFactory()
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)

    result, _ = await invoke_adapter("free-form language waits for Phase D")

    assert result["status"] == "needs_more_info"
    assert factory.clients[0].calls == []


@pytest.mark.asyncio
async def test_business_failure_is_not_transport_failure(monkeypatch):
    factory = RecordingToolClientFactory(
        [
            {
                "search_spaces": {
                    "success": False,
                    "data": None,
                    "error": {
                        "code": "INVALID_TIME",
                        "message": "invalid window",
                    },
                }
            }
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(PlannerDecision(intent="search_spaces", arguments={})),
    )

    result, _ = await invoke_adapter("find a room")

    assert result["status"] == "needs_more_info"
    assert result["error"] is None
    assert result["actions_taken"][0]["error_code"] == "INVALID_TIME"


@pytest.mark.asyncio
async def test_transport_failure_is_mapped_and_client_closes(monkeypatch):
    factory = RecordingToolClientFactory(
        [
            {
                "search_spaces": ToolClientTransportError("backend down"),
            }
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(PlannerDecision(intent="search_spaces", arguments={})),
    )

    result, _ = await invoke_adapter("find a room")

    assert result["status"] == "failed"
    assert result["error"] == "FACILITIES_MCP_UNAVAILABLE"
    assert factory.clients[0].close_count == 1


@pytest.mark.asyncio
async def test_outbound_authentication_failure_is_mapped(monkeypatch):
    factory = RecordingToolClientFactory(
        [
            {
                "search_spaces": ToolClientAuthenticationError("rejected"),
            }
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(PlannerDecision(intent="search_spaces", arguments={})),
    )

    result, _ = await invoke_adapter("find a room")

    assert result["status"] == "failed"
    assert result["error"] == "FACILITIES_AUTHENTICATION_FAILED"


@pytest.mark.asyncio
async def test_booking_confirmation_uses_frozen_arguments(monkeypatch):
    original_arguments = {
        "spaceId": 4,
        "startDateTime": "2099-08-11T14:00:00",
        "endDateTime": "2099-08-11T16:00:00",
        "checkAvailability": False,
    }
    factory = RecordingToolClientFactory(
        [
            {},
            {
                "create_booking": {
                    "success": True,
                    "data": {"bookingId": 77, "status": "CONFIRMED"},
                    "error": None,
                }
            },
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(
            PlannerDecision(intent="book_space", arguments=original_arguments)
        ),
    )

    pending, _ = await invoke_adapter("book this room")
    confirmation_id = pending["confirmation_required"]["confirmation_id"]
    original_arguments["spaceId"] = 999

    confirmed, _ = await invoke_adapter(
        "ignore the previous room and use 999",
        confirmed=True,
        confirmation_id=confirmation_id,
    )

    assert pending["status"] == "needs_confirmation"
    assert confirmed["status"] == "completed"
    assert factory.clients[1].calls == [
        (
            "create_booking",
            {
                "spaceId": 4,
                "startDateTime": "2099-08-11T14:00:00",
                "endDateTime": "2099-08-11T16:00:00",
            },
        )
    ]


@pytest.mark.asyncio
async def test_cancellation_requires_confirmation(monkeypatch):
    factory = RecordingToolClientFactory(
        [
            {
                "get_booking_status": {
                    "success": True,
                    "data": {
                        "bookingId": 9,
                        "status": "CONFIRMED",
                        "startDateTime": "2099-08-11T14:00:00",
                        "endDateTime": "2099-08-11T16:00:00",
                        "space": {"name": "COM2 Project Room"},
                    },
                    "error": None,
                }
            }
        ]
    )
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(
            PlannerDecision(intent="cancel_booking", arguments={"bookingId": 9})
        ),
    )

    result, _ = await invoke_adapter("cancel booking 9")

    assert result["status"] == "needs_confirmation"
    assert result["confirmation_required"]["action"] == "cancel_booking"
    assert factory.clients[0].calls[0][0] == "get_booking_status"


@pytest.mark.asyncio
async def test_maintenance_submission_requires_confirmation(monkeypatch):
    factory = RecordingToolClientFactory()
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(
            PlannerDecision(
                intent="submit_maintenance_request",
                arguments={
                    "spaceId": 4,
                    "facilityType": "projector",
                    "description": "Projector cannot turn on",
                    "priority": "HIGH",
                },
            )
        ),
    )

    result, _ = await invoke_adapter("report projector")

    assert result["status"] == "needs_confirmation"
    assert result["confirmation_required"]["action"] == "submit_maintenance_request"
    assert factory.clients[0].calls == []


@pytest.mark.asyncio
async def test_invalid_confirmation_is_rejected_without_tool_call(monkeypatch):
    factory = RecordingToolClientFactory()
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)

    result, _ = await invoke_adapter(
        "confirm",
        confirmed=True,
        confirmation_id="missing-confirmation",
    )

    assert result["status"] == "needs_more_info"
    assert factory.clients[0].calls == []


@pytest.mark.asyncio
async def test_expired_confirmation_is_rejected(monkeypatch):
    class Clock:
        value = datetime(2026, 8, 10, tzinfo=timezone.utc)

        def __call__(self):
            return self.value

    clock = Clock()
    store = ConfirmationStore(ttl_seconds=1, now_provider=clock)
    monkeypatch.setattr(facilities_server, "_confirmation_store", store)
    factory = RecordingToolClientFactory([{}, {}])
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(
            PlannerDecision(
                intent="book_space",
                arguments={
                    "spaceId": 4,
                    "startDateTime": "2099-08-11T14:00:00",
                    "endDateTime": "2099-08-11T16:00:00",
                    "checkAvailability": False,
                },
            )
        ),
    )
    pending, _ = await invoke_adapter("book")
    clock.value += timedelta(seconds=2)

    result, _ = await invoke_adapter(
        "confirm",
        confirmed=True,
        confirmation_id=pending["confirmation_required"]["confirmation_id"],
    )

    assert result["status"] == "needs_more_info"
    assert factory.clients[1].calls == []


@pytest.mark.asyncio
async def test_confirmation_user_mismatch_is_rejected(monkeypatch):
    factory = RecordingToolClientFactory([{}, {}])
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(
            PlannerDecision(
                intent="book_space",
                arguments={
                    "spaceId": 4,
                    "startDateTime": "2099-08-11T14:00:00",
                    "endDateTime": "2099-08-11T16:00:00",
                    "checkAvailability": False,
                },
            )
        ),
    )
    pending, _ = await invoke_adapter("book")

    result, _ = await invoke_adapter(
        "confirm",
        token="other-user-token",
        confirmed=True,
        confirmation_id=pending["confirmation_required"]["confirmation_id"],
    )

    assert result["status"] == "needs_more_info"
    assert factory.clients[1].calls == []


@pytest.mark.asyncio
async def test_confirmation_session_mismatch_is_rejected(monkeypatch):
    factory = RecordingToolClientFactory([{}, {}])
    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_planner_factory",
        planner_factory(
            PlannerDecision(
                intent="book_space",
                arguments={
                    "spaceId": 4,
                    "startDateTime": "2099-08-11T14:00:00",
                    "endDateTime": "2099-08-11T16:00:00",
                    "checkAvailability": False,
                },
            )
        ),
    )
    pending, _ = await invoke_adapter("book", session_id="session-a")

    result, _ = await invoke_adapter(
        "confirm",
        session_id="session-b",
        confirmed=True,
        confirmation_id=pending["confirmation_required"]["confirmation_id"],
    )

    assert result["status"] == "needs_more_info"
    assert factory.clients[1].calls == []


@pytest.mark.asyncio
async def test_one_client_scope_can_make_multiple_tool_calls(monkeypatch):
    factory = RecordingToolClientFactory(
        [
            {
                "search_spaces": {
                    "success": True,
                    "data": [],
                    "error": None,
                },
                "get_space_details": {
                    "success": True,
                    "data": {"spaceId": 4},
                    "error": None,
                },
            }
        ]
    )

    class MultiToolService:
        def __init__(self, tool_client):
            self.tool_client = tool_client

        async def invoke(self, request, authenticated_user_id, request_id):
            await self.tool_client.call_tool("search_spaces", {})
            await self.tool_client.call_tool("get_space_details", {"spaceId": 4})
            return InvokeResponse(
                response="two calls complete",
                status="completed",
                request_id=request_id,
            )

    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_service_factory",
        lambda planner, client, store: MultiToolService(client),
    )

    result, _ = await invoke_adapter("multi")

    assert result["status"] == "completed"
    assert len(factory.clients) == 1
    assert factory.clients[0].enter_count == 1
    assert [name for name, _ in factory.clients[0].calls] == [
        "search_spaces",
        "get_space_details",
    ]
    assert factory.clients[0].close_count == 1


@pytest.mark.asyncio
async def test_client_closes_when_service_raises(monkeypatch):
    factory = RecordingToolClientFactory()

    class FailingService:
        async def invoke(self, request, authenticated_user_id, request_id):
            raise RuntimeError("service failed")

    monkeypatch.setattr(facilities_server, "_tool_client_factory", factory)
    monkeypatch.setattr(
        facilities_server,
        "_service_factory",
        lambda planner, client, store: FailingService(),
    )

    result, _ = await invoke_adapter("fail")

    assert result["status"] == "failed"
    assert result["error"] == "FACILITIES_ADAPTER_ERROR"
    assert factory.clients[0].close_count == 1


def test_internal_spring_allowlist_remains_exactly_ten_tools():
    assert len(FACILITIES_TOOL_NAMES) == 10
    assert "update_maintenance_status" not in FACILITIES_TOOL_NAMES
