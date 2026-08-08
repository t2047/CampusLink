"""Lost & Found Agent HTTP 入口。"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse

from .config import Settings, get_settings
from .confirmation import ConfirmationStore
from .events import AgentEvent, EventStore
from .models import InvokeRequest, InvokeResponse
from .rate_limit import RateLimiter
from .rules import RuleEngine
from .security import AgentSecurity
from .tools import CampusApiClient


def create_app(
    settings: Settings | None = None,
    api_client: CampusApiClient | None = None,
) -> FastAPI:
    active_settings = settings or get_settings()
    security = AgentSecurity(active_settings)
    limiter = RateLimiter(
        active_settings.agent_rate_limit_per_minute,
        active_settings.agent_rate_limit_per_session,
    )
    event_store = EventStore(active_settings.agent_event_ttl_seconds)
    owns_api_client = api_client is None
    active_api_client = api_client or CampusApiClient(active_settings)
    rule_engine = RuleEngine(
        active_api_client,
        ConfirmationStore(ttl_seconds=600),
        active_settings.lost_found_match_min_score,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            yield
        finally:
            if owns_api_client:
                await active_api_client.close()

    app = FastAPI(
        title="CampusLink Lost & Found Agent",
        version=active_settings.agent_version,
        lifespan=lifespan,
    )

    @app.get("/health")
    async def health() -> dict[str, object]:
        return {
            "status": "ok",
            "service": active_settings.agent_name,
            "version": active_settings.agent_version,
            "mode": active_settings.effective_mode,
            "model_configured": bool(active_settings.lost_found_llm_api_key.strip()),
        }

    @app.get("/agent/capabilities")
    async def capabilities() -> dict[str, object]:
        return {
            "agent": active_settings.agent_name,
            "version": active_settings.agent_version,
            "status": "rules_ready",
            "capabilities": {
                "domains": ["lost_and_found"],
                "actions": [
                    "report_lost",
                    "search_found_items",
                    "get_item_detail",
                    "claim_item",
                ],
                "languages": ["zh", "en"],
                "write_confirmation_required": True,
            },
        }

    @app.post("/agent/invoke", response_model=InvokeResponse)
    async def invoke(payload: InvokeRequest, request: Request) -> InvokeResponse:
        verified = await security.verify(request, "invoke")
        request_id = payload.trace_parent.trace_id or verified.trace_id or str(uuid4())
        session_id = payload.conversation_context.session_id or request_id
        limiter.check(verified.user_id, session_id)
        event_store.append(
            request_id,
            AgentEvent(
                "agent_start", {"agent": active_settings.agent_name, "message": payload.message}
            ),
        )
        try:
            response = await rule_engine.handle(
                payload,
                verified,
                request_id,
                lambda event: event_store.append(request_id, event),
            )
        except Exception:
            response = InvokeResponse(
                response="Agent 处理请求时发生内部错误。",
                status="failed",
                request_id=request_id,
            )
            event_store.append(
                request_id,
                AgentEvent(
                    "agent_error",
                    {"code": "INTERNAL_ERROR", "message": response.response},
                ),
            )
        event_store.append(
            request_id,
            AgentEvent(
                "agent_done",
                {"status": response.status, "response_summary": response.response},
            ),
        )
        return response

    @app.get("/agent/stream")
    async def stream(request_id: str, request: Request) -> StreamingResponse:
        await security.verify(request, "stream")
        return StreamingResponse(event_store.stream(request_id), media_type="text/event-stream")

    return app


app = create_app()
