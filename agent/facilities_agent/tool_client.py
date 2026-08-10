"""Transport-neutral Facilities tool client interface and test fake."""

from abc import ABC, abstractmethod
from copy import deepcopy
from typing import Any, Callable, Dict, List, Mapping, Union


FACILITIES_TOOL_NAMES = frozenset(
    {
        "search_spaces",
        "get_space_details",
        "check_availability",
        "create_booking",
        "list_user_bookings",
        "get_booking_status",
        "cancel_booking",
        "submit_maintenance_request",
        "get_maintenance_status",
        "list_user_maintenance_requests",
    }
)


class ToolClientError(RuntimeError):
    code = "FACILITIES_MCP_UNAVAILABLE"


class ToolClientTimeoutError(ToolClientError):
    code = "FACILITIES_MCP_TIMEOUT"


class ToolClientTransportError(ToolClientError):
    code = "FACILITIES_MCP_UNAVAILABLE"


class ToolClientAuthenticationError(ToolClientError):
    code = "FACILITIES_AUTHENTICATION_FAILED"


class ToolClientProtocolError(ToolClientError):
    code = "FACILITIES_MCP_PROTOCOL_ERROR"


class ToolClient(ABC):
    @abstractmethod
    async def call_tool(
        self, tool_name: str, arguments: Dict[str, Any]
    ) -> Dict[str, Any]:
        """Call one of the ten Spring Facilities MCP tools."""


Fixture = Union[
    Dict[str, Any],
    Exception,
    Callable[[Dict[str, Any]], Dict[str, Any]],
]


class FakeFacilitiesToolClient(ToolClient):
    """Small fixture fake; it intentionally contains no Facilities business rules."""

    def __init__(
        self, fixtures: Mapping[str, Union[Fixture, List[Fixture]]] = None
    ) -> None:
        self._fixtures: Dict[str, List[Fixture]] = {}
        self.calls: List[Dict[str, Any]] = []
        for tool_name, configured in (fixtures or {}).items():
            values = configured if isinstance(configured, list) else [configured]
            self._fixtures[tool_name] = list(values)

    def queue_response(self, tool_name: str, response: Fixture) -> None:
        self._fixtures.setdefault(tool_name, []).append(response)

    async def call_tool(
        self, tool_name: str, arguments: Dict[str, Any]
    ) -> Dict[str, Any]:
        if tool_name not in FACILITIES_TOOL_NAMES:
            raise ValueError("Unsupported Facilities tool: {0}".format(tool_name))
        if "userId" in arguments or "user_id" in arguments:
            raise AssertionError(
                "Authenticated identity must not be sent in tool arguments"
            )

        safe_arguments = deepcopy(arguments)
        self.calls.append({"tool_name": tool_name, "arguments": safe_arguments})

        configured = self._fixtures.get(tool_name, [])
        if not configured:
            return {"success": True, "data": None, "error": None}

        fixture = configured.pop(0) if len(configured) > 1 else configured[0]
        if isinstance(fixture, Exception):
            raise fixture
        if callable(fixture):
            return deepcopy(fixture(safe_arguments))
        return deepcopy(fixture)
