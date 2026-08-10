"""Facilities Domain Agent MCP Server.

This server exposes one public ``invoke`` tool to Chat Core and uses the strict
DeepSeek planner for natural-language intent extraction. The ten Spring
Facilities tools remain private behind the Adapter.
"""

from __future__ import annotations

import contextlib
import json
import logging
import os
import sys
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from pydantic import ValidationError

_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from dotenv import find_dotenv, load_dotenv
from facilities_agent.confirmation import ConfirmationStore
from facilities_agent.deepseek_planner import DeepSeekPlanner
from facilities_agent.mcp_tool_client import (
    DEFAULT_FACILITIES_MCP_URL,
    FacilitiesMcpToolClient,
)
from facilities_agent.models import InvokeRequest, InvokeResponse
from facilities_agent.planner import FacilitiesPlanner, PlannerError
from facilities_agent.result_mapper import map_technical_error
from facilities_agent.service import FacilitiesAdapterService
from facilities_agent.tool_client import ToolClientError
from mcp.server.fastmcp import Context, FastMCP

from mcp_servers.security import (
    McpSecurityMiddleware,
    token_and_identity_from_context,
)

load_dotenv(find_dotenv())

logger = logging.getLogger(__name__)

AGENT_NAME = "facility-agent"
DEFAULT_PORT = 8082


PlannerFactory = Callable[[InvokeRequest], FacilitiesPlanner]
ToolClientFactory = Callable[[str], FacilitiesMcpToolClient]
ServiceFactory = Callable[
    [FacilitiesPlanner | None, Any, ConfirmationStore], FacilitiesAdapterService
]


def _default_planner_factory(_request: InvokeRequest) -> FacilitiesPlanner:
    return DeepSeekPlanner.from_environment()


def _default_tool_client_factory(token: str) -> FacilitiesMcpToolClient:
    return FacilitiesMcpToolClient(token)


def _default_service_factory(
    planner: FacilitiesPlanner | None,
    tool_client: Any,
    confirmation_store: ConfirmationStore,
) -> FacilitiesAdapterService:
    return FacilitiesAdapterService(planner, tool_client, confirmation_store)


_planner_factory: PlannerFactory = _default_planner_factory
_tool_client_factory: ToolClientFactory = _default_tool_client_factory
_service_factory: ServiceFactory = _default_service_factory
_confirmation_store = ConfirmationStore()

mcp = FastMCP(f"{AGENT_NAME}-server", streamable_http_path="/")
_streamable_app = mcp.streamable_http_app()


def _serialize(response: InvokeResponse) -> str:
    return json.dumps(
        response.model_dump(by_alias=True, mode="json"),
        ensure_ascii=False,
    )


def _failed_response(request_id: str, code: str, message: str) -> InvokeResponse:
    return InvokeResponse(
        response=message,
        status="failed",
        shared_context={},
        actions_taken=[],
        request_id=request_id,
        error=code,
    )


async def _invoke_adapter(
    message: str,
    conversation_context: dict | None,
    confirmed: bool,
    confirmation_id: str | None,
    trace_parent: dict | None,
    context: Context | Any | None,
) -> str:
    request_id = f"facility-invoke-{uuid.uuid4().hex}"
    if context is None:
        return _serialize(
            _failed_response(
                request_id,
                "FACILITIES_AUTHENTICATION_FAILED",
                "Facilities authentication context is missing.",
            )
        )

    try:
        delegation_token, claims = token_and_identity_from_context(context, AGENT_NAME)
    except ValueError:
        return _serialize(
            _failed_response(
                request_id,
                "FACILITIES_AUTHENTICATION_FAILED",
                "Facilities authentication context is invalid.",
            )
        )

    try:
        request = InvokeRequest(
            message=message,
            conversation_context=conversation_context or {},
            confirmed=confirmed,
            confirmation_id=confirmation_id,
            trace_parent=trace_parent,
        )
    except ValidationError:
        return _serialize(
            _failed_response(
                request_id,
                "VALIDATION_ERROR",
                "The Facilities invoke request is invalid.",
            )
        )

    try:
        planner = None if request.confirmed else _planner_factory(request)
        async with _tool_client_factory(delegation_token) as tool_client:
            service = _service_factory(planner, tool_client, _confirmation_store)
            response = await service.invoke(
                request,
                authenticated_user_id=claims["sub"],
                request_id=request_id,
            )
    except (ToolClientError, PlannerError) as error:
        response = map_technical_error(error, {}, request_id)
    except Exception:
        logger.exception("Facilities invoke failed: request_id=%s", request_id)
        response = _failed_response(
            request_id,
            "FACILITIES_ADAPTER_ERROR",
            "The facilities service is temporarily unavailable.",
        )
    return _serialize(response)


@mcp.tool()
async def invoke(
    message: str,
    conversation_context: dict | None = None,
    confirmed: bool = False,
    confirmation_id: str | None = None,
    trace_parent: dict | None = None,
    context: Context | None = None,
) -> str:
    """Invoke the Facilities Domain Agent Adapter.

    Args:
        message: User message for the Facilities domain.
        conversation_context: Stable session ID and cross-agent shared data.
        confirmed: Whether this request confirms a pending write action.
        confirmation_id: Frozen pending action identifier returned previously.
        trace_parent: Optional distributed tracing identifiers.

    Returns:
        JSON containing response, status, confirmation_required, shared_context,
        actions_taken, request_id, and error.
    """
    try:
        return await _invoke_adapter(
            message,
            conversation_context,
            confirmed,
            confirmation_id,
            trace_parent,
            context,
        )
    except Exception:  # noqa: BLE001 - 任何未预期异常都转为 failed 响应，
        # 绝不向 mcp SDK 抛异常（SDK 在工具异常路径存在 cancel-scope 时序 bug，
        # 会导致 streamable HTTP session 崩溃、SSE 流中断、前端一直“回复中”）。
        logger.exception("Unhandled exception in facilities invoke")
        return _serialize(
            _failed_response(
                f"facility-invoke-{uuid.uuid4().hex}",
                "FACILITIES_ADAPTER_ERROR",
                "The facilities service is temporarily unavailable.",
            )
        )


@contextlib.asynccontextmanager
async def _lifespan(_app: FastAPI):
    async with mcp.session_manager.run():
        yield


app = FastAPI(
    title="CampusLink Facilities Domain Agent",
    version="1.0.0",
    lifespan=_lifespan,
)
app.add_middleware(McpSecurityMiddleware, agent_name=AGENT_NAME)
app.mount("/mcp", _streamable_app)


@app.get("/health")
async def health() -> dict[str, object]:
    return {"status": "ok", "service": AGENT_NAME, "mcp": True}


def main() -> None:
    import uvicorn

    if not os.environ.get("FACILITIES_MCP_URL"):
        print(
            "[facility-agent] WARNING: 未设置 FACILITIES_MCP_URL，将使用默认后端地址 "
            f"{DEFAULT_FACILITIES_MCP_URL}（后端 Spring AI MCP）。"
            "若 backend 不在本机 8080，请设置 FACILITIES_MCP_URL（如 http://<host>:8080/mcp）。",
            file=sys.stderr,
        )
    port = int(os.environ.get("FACILITIES_AGENT_PORT", str(DEFAULT_PORT)))
    uvicorn.run(
        "mcp_servers.facilities_server:app",
        host="0.0.0.0",
        port=port,
    )


if __name__ == "__main__":
    main()
