import json
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import patch

import httpx
from mcp.shared.exceptions import McpError
from mcp.types import CallToolResult, ErrorData, TextContent

from agent.facilities_agent.mcp_tool_client import (
    DEFAULT_FACILITIES_MCP_URL,
    FACILITIES_MCP_URL_ENV,
    FacilitiesMcpToolClient,
)
from agent.facilities_agent.tool_client import (
    FACILITIES_TOOL_NAMES,
    ToolClientAuthenticationError,
    ToolClientProtocolError,
    ToolClientTimeoutError,
    ToolClientTransportError,
)

TOKEN = "delegation-token-value-that-must-stay-private"
SUCCESS_ENVELOPE = {
    "success": True,
    "data": {"spaceId": 4},
    "error": None,
}
BUSINESS_FAILURE_ENVELOPE = {
    "success": False,
    "data": None,
    "error": {
        "code": "BOOKING_CONFLICT",
        "message": "The requested time overlaps an existing booking",
    },
}


def text_result(envelope):
    return CallToolResult(
        content=[TextContent(type="text", text=json.dumps(envelope))]
    )


class FakeSession:
    def __init__(self, call_result=None):
        self.initialize_calls = 0
        self.calls = []
        self.list_cursors = []
        self.call_result = call_result or text_result(SUCCESS_ENVELOPE)
        self.initialize_error = None
        self.call_error = None
        self.list_error = None
        self.list_results = [
            SimpleNamespace(
                tools=[SimpleNamespace(name=name) for name in sorted(FACILITIES_TOOL_NAMES)],
                nextCursor=None,
            )
        ]

    async def initialize(self):
        self.initialize_calls += 1
        if self.initialize_error:
            raise self.initialize_error
        return SimpleNamespace()

    async def call_tool(self, tool_name, arguments):
        self.calls.append((tool_name, arguments))
        if self.call_error:
            raise self.call_error
        return self.call_result

    async def list_tools(self, cursor=None):
        self.list_cursors.append(cursor)
        if self.list_error:
            raise self.list_error
        return self.list_results.pop(0)


class FakeSessionScopeFactory:
    def __init__(self, session=None, enter_error=None):
        self.session = session or FakeSession()
        self.enter_error = enter_error
        self.calls = []
        self.entered = 0
        self.exited = 0

    def __call__(self, url, headers, timeout):
        self.calls.append(
            {"url": url, "headers": dict(headers), "timeout": timeout}
        )

        @asynccontextmanager
        async def scope():
            self.entered += 1
            try:
                if self.enter_error:
                    raise self.enter_error
                yield self.session
            finally:
                self.exited += 1

        return scope()


def http_status_error(status, message="request rejected"):
    request = httpx.Request("POST", "http://localhost:8080/mcp")
    response = httpx.Response(status, request=request)
    return httpx.HTTPStatusError(message, request=request, response=response)


class FacilitiesMcpToolClientTest(unittest.IsolatedAsyncioTestCase):
    def build_client(self, session=None, factory=None, **kwargs):
        scope_factory = factory or FakeSessionScopeFactory(session)
        client = FacilitiesMcpToolClient(
            TOKEN,
            url=kwargs.pop("url", "http://localhost:8080/mcp"),
            _session_scope_factory=scope_factory,
            **kwargs,
        )
        return client, scope_factory

    async def test_all_ten_allowlisted_tools_can_be_called(self):
        session = FakeSession()
        client, _ = self.build_client(session)

        async with client:
            for name in sorted(FACILITIES_TOOL_NAMES):
                await client.call_tool(name, {})

        self.assertEqual(10, len(session.calls))
        self.assertEqual(FACILITIES_TOOL_NAMES, {name for name, _ in session.calls})

    async def test_unknown_tool_is_rejected_before_session_call(self):
        session = FakeSession()
        client, _ = self.build_client(session)

        async with client:
            with self.assertRaisesRegex(ValueError, "Unsupported Facilities tool"):
                await client.call_tool("update_maintenance_status", {})

        self.assertEqual([], session.calls)

    async def test_authorization_header_forwards_same_delegation_token(self):
        client, factory = self.build_client()

        async with client:
            pass

        self.assertEqual(
            f"Bearer {TOKEN}",
            factory.calls[0]["headers"]["Authorization"],
        )

    async def test_token_is_not_added_to_tool_arguments(self):
        session = FakeSession()
        client, _ = self.build_client(session)

        async with client:
            await client.call_tool("get_space_details", {"spaceId": 4})

        arguments = session.calls[0][1]
        self.assertEqual({"spaceId": 4}, arguments)
        self.assertNotIn(TOKEN, repr(arguments))

    async def test_token_is_not_exposed_by_repr(self):
        client, _ = self.build_client()

        rendered = repr(client)

        self.assertNotIn(TOKEN, rendered)
        self.assertIn("http://localhost:8080/mcp", rendered)

    async def test_token_is_not_exposed_by_transport_error(self):
        session = FakeSession()
        session.call_error = http_status_error(401, "rejected " + TOKEN)
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientAuthenticationError) as captured:
            async with client:
                await client.call_tool("search_spaces", {})

        self.assertNotIn(TOKEN, str(captured.exception))

    async def test_initialize_runs_once_for_multiple_calls(self):
        session = FakeSession()
        client, _ = self.build_client(session)

        async with client:
            await client.call_tool("search_spaces", {})
            await client.call_tool("check_availability", {"spaceId": 4})
            await client.call_tool("create_booking", {"spaceId": 4})

        self.assertEqual(1, session.initialize_calls)
        self.assertEqual(3, len(session.calls))

    async def test_normal_context_exit_closes_scope(self):
        client, factory = self.build_client()

        async with client:
            self.assertEqual(1, factory.entered)
            self.assertEqual(0, factory.exited)

        self.assertEqual(1, factory.exited)

    async def test_exception_inside_context_still_closes_scope(self):
        client, factory = self.build_client()

        with self.assertRaisesRegex(RuntimeError, "adapter failed"):
            async with client:
                raise RuntimeError("adapter failed")

        self.assertEqual(1, factory.exited)

    async def test_client_instance_cannot_be_reentered(self):
        client, _ = self.build_client()
        async with client:
            pass

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                pass

    async def test_call_requires_request_scope(self):
        client, _ = self.build_client()

        with self.assertRaises(ToolClientProtocolError):
            await client.call_tool("search_spaces", {})

    async def test_close_is_idempotent(self):
        client, factory = self.build_client()
        async with client:
            await client.close()
            await client.close()

        self.assertEqual(1, factory.exited)

    async def test_text_json_success_envelope_is_parsed(self):
        client, _ = self.build_client(FakeSession(text_result(SUCCESS_ENVELOPE)))

        async with client:
            result = await client.call_tool("search_spaces", {})

        self.assertEqual(SUCCESS_ENVELOPE, result)

    async def test_text_json_business_failure_is_returned_not_raised(self):
        client, _ = self.build_client(
            FakeSession(text_result(BUSINESS_FAILURE_ENVELOPE))
        )

        async with client:
            result = await client.call_tool("create_booking", {})

        self.assertEqual(BUSINESS_FAILURE_ENVELOPE, result)

    async def test_structured_content_success_is_parsed(self):
        response = CallToolResult(
            content=[], structuredContent=SUCCESS_ENVELOPE
        )
        client, _ = self.build_client(FakeSession(response))

        async with client:
            result = await client.call_tool("search_spaces", {})

        self.assertEqual(SUCCESS_ENVELOPE, result)

    async def test_snake_case_structured_content_list_is_parsed(self):
        response = SimpleNamespace(
            isError=False,
            content=[],
            structured_content=[{"value": SUCCESS_ENVELOPE}],
        )
        client, _ = self.build_client(FakeSession(response))

        async with client:
            result = await client.call_tool("search_spaces", {})

        self.assertEqual(SUCCESS_ENVELOPE, result)

    async def test_typed_mcp_object_is_parsed(self):
        class TypedEnvelope:
            def model_dump(self, by_alias=False):
                return SUCCESS_ENVELOPE

        client, _ = self.build_client(FakeSession(TypedEnvelope()))

        async with client:
            result = await client.call_tool("search_spaces", {})

        self.assertEqual(SUCCESS_ENVELOPE, result)

    async def test_malformed_text_json_raises_protocol_error(self):
        response = CallToolResult(
            content=[TextContent(type="text", text="{not-json")]
        )
        client, _ = self.build_client(FakeSession(response))

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_empty_content_raises_protocol_error(self):
        client, _ = self.build_client(FakeSession(CallToolResult(content=[])))

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_unexpected_content_type_raises_protocol_error(self):
        response = SimpleNamespace(
            isError=False,
            structuredContent=None,
            content=[SimpleNamespace(type="image", data="ignored")],
        )
        client, _ = self.build_client(FakeSession(response))

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_mcp_is_error_result_raises_protocol_error(self):
        response = CallToolResult(
            isError=True,
            content=[TextContent(type="text", text="tool failed")],
        )
        client, _ = self.build_client(FakeSession(response))

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_invalid_failure_envelope_raises_protocol_error(self):
        response = text_result({"success": False, "data": None, "error": None})
        client, _ = self.build_client(FakeSession(response))

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_http_401_maps_to_authentication_error(self):
        session = FakeSession()
        session.call_error = http_status_error(401)
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientAuthenticationError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_http_403_maps_to_authentication_error(self):
        session = FakeSession()
        session.call_error = http_status_error(403)
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientAuthenticationError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_timeout_maps_to_timeout_error(self):
        session = FakeSession()
        session.call_error = httpx.ReadTimeout("slow response")
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientTimeoutError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_connection_failure_maps_to_transport_error(self):
        session = FakeSession()
        session.call_error = httpx.ConnectError("connection refused")
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientTransportError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_http_500_maps_to_transport_error(self):
        session = FakeSession()
        session.call_error = http_status_error(500)
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientTransportError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_mcp_error_maps_to_protocol_error(self):
        session = FakeSession()
        session.call_error = McpError(
            ErrorData(code=-32603, message="protocol failure")
        )
        client, _ = self.build_client(session)

        with self.assertRaises(ToolClientProtocolError):
            async with client:
                await client.call_tool("search_spaces", {})

    async def test_http_error_during_connect_is_classified_and_scope_closes(self):
        factory = FakeSessionScopeFactory(enter_error=http_status_error(401))
        client, _ = self.build_client(factory=factory)

        with self.assertRaises(ToolClientAuthenticationError):
            async with client:
                pass

        self.assertEqual(1, factory.exited)

    async def test_camel_case_and_local_datetime_arguments_are_unchanged(self):
        session = FakeSession()
        client, _ = self.build_client(session)
        arguments = {
            "spaceId": 4,
            "startDateTime": "2026-08-11T14:00:00",
            "endDateTime": "2026-08-11T16:00:00",
            "minimumCapacity": 6,
            "spaceType": "STUDY_ROOM",
        }

        async with client:
            await client.call_tool("search_spaces", arguments)

        self.assertEqual(arguments, session.calls[0][1])

    async def test_empty_arguments_are_forwarded_as_empty_dict(self):
        session = FakeSession()
        client, _ = self.build_client(session)

        async with client:
            await client.call_tool("list_user_bookings", {})

        self.assertEqual({}, session.calls[0][1])

    async def test_user_id_arguments_are_rejected(self):
        client, _ = self.build_client()

        async with client:
            for forbidden in ("userId", "user_id"):
                with (
                    self.subTest(forbidden=forbidden),
                    self.assertRaisesRegex(ValueError, "identity"),
                ):
                    await client.call_tool(
                        "list_user_bookings", {forbidden: 123}
                    )

    async def test_list_tools_matches_exact_ten_tool_contract(self):
        client, _ = self.build_client()

        async with client:
            names = await client.list_tools()

        self.assertEqual(FACILITIES_TOOL_NAMES, frozenset(names))
        self.assertEqual(10, len(names))
        self.assertNotIn("update_maintenance_status", names)

    async def test_list_tools_follows_pagination_without_reinitializing(self):
        session = FakeSession()
        session.list_results = [
            SimpleNamespace(
                tools=[SimpleNamespace(name="search_spaces")],
                nextCursor="page-2",
            ),
            SimpleNamespace(
                tools=[SimpleNamespace(name="get_space_details")],
                nextCursor=None,
            ),
        ]
        client, _ = self.build_client(session)

        async with client:
            names = await client.list_tools()

        self.assertEqual(["search_spaces", "get_space_details"], names)
        self.assertEqual([None, "page-2"], session.list_cursors)
        self.assertEqual(1, session.initialize_calls)

    async def test_url_uses_environment_configuration(self):
        with patch.dict(
            "os.environ", {FACILITIES_MCP_URL_ENV: "http://localhost:8080/mcp"}
        ):
            factory = FakeSessionScopeFactory()
            client = FacilitiesMcpToolClient(
                TOKEN, _session_scope_factory=factory
            )
            async with client:
                pass

        self.assertEqual("http://localhost:8080/mcp", factory.calls[0]["url"])

    async def test_explicit_url_overrides_environment_configuration(self):
        with patch.dict(
            "os.environ", {FACILITIES_MCP_URL_ENV: "http://wrong/mcp"}
        ):
            client, factory = self.build_client(
                url="http://localhost:8080/mcp"
            )
            async with client:
                pass

        self.assertEqual("http://localhost:8080/mcp", factory.calls[0]["url"])

    def test_default_url_is_future_docker_backend_endpoint(self):
        with patch.dict("os.environ", {}, clear=True):
            client = FacilitiesMcpToolClient(
                TOKEN, _session_scope_factory=FakeSessionScopeFactory()
            )

        self.assertEqual(DEFAULT_FACILITIES_MCP_URL, client.url)

    def test_missing_token_is_rejected_without_echoing_value(self):
        with self.assertRaises(ToolClientAuthenticationError) as captured:
            FacilitiesMcpToolClient("   ")

        self.assertNotIn(TOKEN, str(captured.exception))
