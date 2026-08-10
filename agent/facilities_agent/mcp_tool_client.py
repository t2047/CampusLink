"""Request-scoped production client for the Spring Facilities MCP server."""

from __future__ import annotations

import json
import os
from builtins import ExceptionGroup
from collections.abc import Callable, Mapping
from contextlib import (
    AbstractAsyncContextManager,
    AsyncExitStack,
    asynccontextmanager,
    suppress,
)
from copy import deepcopy
from typing import Any, Self

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import StreamableHTTPError, streamable_http_client
from mcp.shared.exceptions import McpError

from .tool_client import (
    FACILITIES_TOOL_NAMES,
    ToolClient,
    ToolClientAuthenticationError,
    ToolClientError,
    ToolClientProtocolError,
    ToolClientTimeoutError,
    ToolClientTransportError,
)

FACILITIES_MCP_URL_ENV = "FACILITIES_MCP_URL"
DEFAULT_FACILITIES_MCP_URL = "http://chat-backend:8080/mcp"
DEFAULT_MCP_TIMEOUT = httpx.Timeout(30.0, connect=5.0)

SessionScopeFactory = Callable[
    [str, Mapping[str, str], httpx.Timeout], AbstractAsyncContextManager[Any]
]


@asynccontextmanager
async def _open_mcp_session(
    url: str,
    headers: Mapping[str, str],
    timeout: httpx.Timeout,
):
    """Open one SDK transport/session pair for a single Adapter invoke."""
    async with (
        httpx.AsyncClient(headers=dict(headers), timeout=timeout) as http,
        streamable_http_client(url, http_client=http) as (
            read_stream,
            write_stream,
            _,
        ),
        ClientSession(read_stream, write_stream) as session,
    ):
        yield session


class FacilitiesMcpToolClient(ToolClient):
    """Call the ten Spring Facilities tools with one delegated MCP session.

    A client instance is single-use and must be entered as an async context manager.
    The caller supplies the RS256 delegation token for the current Adapter request;
    this class only forwards it as an Authorization header.
    """

    def __init__(
        self,
        delegation_token: str,
        url: str | None = None,
        *,
        timeout: httpx.Timeout | None = None,
        _session_scope_factory: SessionScopeFactory = _open_mcp_session,
    ) -> None:
        if not isinstance(delegation_token, str) or not delegation_token.strip():
            raise ToolClientAuthenticationError(
                "A Facilities delegation token is required"
            )

        configured_url = url or os.getenv(
            FACILITIES_MCP_URL_ENV, DEFAULT_FACILITIES_MCP_URL
        )
        if not isinstance(configured_url, str) or not configured_url.strip():
            raise ToolClientTransportError("Facilities MCP URL is not configured")

        self._delegation_token = delegation_token.strip()
        self._url = configured_url.strip()
        self._timeout = timeout or DEFAULT_MCP_TIMEOUT
        self._session_scope_factory = _session_scope_factory
        self._stack: AsyncExitStack | None = None
        self._session: Any = None
        self._used = False

    @property
    def url(self) -> str:
        return self._url

    def __repr__(self) -> str:
        state = "open" if self._session is not None else "closed"
        return f"FacilitiesMcpToolClient(url={self._url!r}, state={state!r})"

    async def __aenter__(self) -> Self:
        if self._used:
            raise ToolClientProtocolError(
                "Facilities MCP client instances cannot be reused"
            )
        self._used = True
        stack = AsyncExitStack()
        self._stack = stack
        headers = {"Authorization": f"Bearer {self._delegation_token}"}

        try:
            scope = self._session_scope_factory(self._url, headers, self._timeout)
            self._session = await stack.enter_async_context(scope)
            await self._session.initialize()
            return self
        except Exception as exc:  # noqa: BLE001 - SDK wraps failures in groups
            self._session = None
            self._stack = None
            with suppress(Exception):
                await stack.aclose()
            self._raise_classified(exc)
            raise AssertionError("unreachable")

    async def __aexit__(self, exc_type, exc, traceback) -> bool:
        try:
            await self.close()
        except ToolClientError:
            if exc is None:
                raise
        return False

    async def close(self) -> None:
        """Close the session and HTTP transport; safe to call more than once."""
        stack = self._stack
        self._stack = None
        self._session = None
        if stack is None:
            return
        try:
            await stack.aclose()
        except Exception as exc:  # noqa: BLE001 - context exit may wrap failures
            self._raise_classified(exc)

    async def call_tool(
        self, tool_name: str, arguments: dict[str, Any]
    ) -> dict[str, Any]:
        """Call an allowlisted tool and return the Spring business envelope."""
        session = self._require_open_session()
        self._validate_tool_name(tool_name)
        safe_arguments = self._validate_arguments(arguments)

        try:
            result = await session.call_tool(tool_name, safe_arguments)
        except Exception as exc:  # noqa: BLE001 - classify all SDK failures
            self._raise_classified(exc)
            raise AssertionError("unreachable")
        return self._parse_result(result)

    async def list_tools(self) -> list[str]:
        """List all server tool names, following MCP pagination when present."""
        session = self._require_open_session()
        names: list[str] = []
        cursor = None
        seen_cursors: set[str] = set()

        while True:
            try:
                result = await session.list_tools(cursor=cursor)
            except Exception as exc:  # noqa: BLE001 - classify all SDK failures
                self._raise_classified(exc)
                raise AssertionError("unreachable")

            tools = self._member(result, "tools")
            if not isinstance(tools, list):
                raise ToolClientProtocolError(
                    "Facilities MCP tools/list response is invalid"
                )
            for tool in tools:
                name = self._member(tool, "name")
                if not isinstance(name, str) or not name:
                    raise ToolClientProtocolError(
                        "Facilities MCP tools/list response contains an invalid tool"
                    )
                names.append(name)

            next_cursor = self._member(result, "nextCursor", "next_cursor")
            if not next_cursor:
                return names
            if not isinstance(next_cursor, str) or next_cursor in seen_cursors:
                raise ToolClientProtocolError(
                    "Facilities MCP tools/list pagination is invalid"
                )
            seen_cursors.add(next_cursor)
            cursor = next_cursor

    def _require_open_session(self):
        if self._session is None:
            raise ToolClientProtocolError(
                "Facilities MCP client must be used inside 'async with'"
            )
        return self._session

    @staticmethod
    def _validate_tool_name(tool_name: str) -> None:
        if tool_name not in FACILITIES_TOOL_NAMES:
            raise ValueError(f"Unsupported Facilities tool: {tool_name}")

    @staticmethod
    def _validate_arguments(arguments: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(arguments, dict):
            raise TypeError("Facilities tool arguments must be a dict")
        if "userId" in arguments or "user_id" in arguments:
            raise ValueError(
                "Authenticated identity must not be sent in tool arguments"
            )
        return deepcopy(arguments)

    def _parse_result(self, result: Any) -> dict[str, Any]:
        if self._member(result, "isError", "is_error") is True:
            raise ToolClientProtocolError("Facilities MCP tool returned an MCP error")

        direct = self._find_envelope(result)
        if direct is not None:
            return self._validate_envelope(direct)

        structured = self._member(
            result, "structuredContent", "structured_content"
        )
        envelope = self._find_envelope(structured)
        if envelope is not None:
            return self._validate_envelope(envelope)

        content = self._member(result, "content")
        if not isinstance(content, list) or not content:
            raise ToolClientProtocolError("Facilities MCP response is empty")

        texts: list[str] = []
        for item in content:
            if self._member(item, "type") == "text":
                text = self._member(item, "text")
                if not isinstance(text, str):
                    raise ToolClientProtocolError(
                        "Facilities MCP text content is invalid"
                    )
                texts.append(text)

        if not texts:
            raise ToolClientProtocolError(
                "Facilities MCP response has no supported content"
            )

        raw_text = "\n".join(texts).strip()
        if not raw_text:
            raise ToolClientProtocolError("Facilities MCP response is empty")
        try:
            parsed = json.loads(raw_text)
            if isinstance(parsed, str):
                parsed = json.loads(parsed)
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            raise ToolClientProtocolError(
                "Facilities MCP response is not valid JSON"
            ) from exc

        envelope = self._find_envelope(parsed)
        if envelope is None:
            raise ToolClientProtocolError(
                "Facilities MCP response does not contain a business envelope"
            )
        return self._validate_envelope(envelope)

    def _find_envelope(self, value: Any, depth: int = 0) -> dict[str, Any] | None:
        if value is None or depth > 3:
            return None

        mapping = self._as_mapping(value)
        if mapping is not None:
            if "success" in mapping:
                return dict(mapping)
            for key in ("result", "value", "data"):
                if key in mapping:
                    found = self._find_envelope(mapping[key], depth + 1)
                    if found is not None:
                        return found

        if isinstance(value, (list, tuple)):
            for item in value:
                found = self._find_envelope(item, depth + 1)
                if found is not None:
                    return found
        return None

    @staticmethod
    def _validate_envelope(envelope: dict[str, Any]) -> dict[str, Any]:
        success = envelope.get("success")
        if not isinstance(success, bool):
            raise ToolClientProtocolError(
                "Facilities MCP envelope has an invalid success field"
            )
        if success is False:
            error = envelope.get("error")
            if not isinstance(error, Mapping):
                raise ToolClientProtocolError(
                    "Facilities MCP failure envelope has no error object"
                )
            if not isinstance(error.get("code"), str) or not isinstance(
                error.get("message"), str
            ):
                raise ToolClientProtocolError(
                    "Facilities MCP failure envelope has an invalid error object"
                )
        return deepcopy(envelope)

    @classmethod
    def _as_mapping(cls, value: Any) -> Mapping[str, Any] | None:
        if isinstance(value, Mapping):
            return value
        model_dump = getattr(value, "model_dump", None)
        if callable(model_dump):
            dumped = model_dump(by_alias=True)
            return dumped if isinstance(dumped, Mapping) else None
        return None

    @classmethod
    def _member(cls, value: Any, *names: str) -> Any:
        if isinstance(value, Mapping):
            for name in names:
                if name in value:
                    return value[name]
            return None
        for name in names:
            if hasattr(value, name):
                return getattr(value, name)
        return None

    @classmethod
    def _raise_classified(cls, exc: Exception) -> None:
        root = cls._root_exception(exc)
        if isinstance(root, ToolClientError):
            raise root
        if isinstance(root, httpx.HTTPStatusError):
            if root.response.status_code in (401, 403):
                raise ToolClientAuthenticationError(
                    "Facilities MCP authentication was rejected"
                ) from None
            raise ToolClientTransportError(
                "Facilities MCP returned an HTTP transport error"
            ) from None
        if isinstance(root, (httpx.TimeoutException, TimeoutError)):
            raise ToolClientTimeoutError("Facilities MCP request timed out") from None
        if isinstance(root, httpx.TransportError):
            raise ToolClientTransportError(
                "Facilities MCP transport is unavailable"
            ) from None
        if isinstance(root, (McpError, StreamableHTTPError)):
            raise ToolClientProtocolError(
                "Facilities MCP protocol request failed"
            ) from None
        if isinstance(root, OSError):
            raise ToolClientTransportError(
                "Facilities MCP transport is unavailable"
            ) from None
        raise ToolClientProtocolError("Facilities MCP protocol request failed") from None

    @classmethod
    def _root_exception(cls, exc: Exception) -> Exception:
        current = exc
        while isinstance(current, ExceptionGroup) and current.exceptions:
            current = current.exceptions[0]
        return current
