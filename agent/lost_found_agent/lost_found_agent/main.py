"""Lost & Found Agent HTTP 入口。"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse

from .config import Settings, get_settings
from .confirmation import ConfirmationStore
from .events import AgentEvent, EventStore
from .llm import LlmInterpreter, LlmUnavailable, interpret_with_retry
from .models import ClassifyRequest, ClassifyResponse, InvokeRequest, InvokeResponse
from .rate_limit import RateLimiter
from .rules import RuleEngine, map_category
from .security import AgentSecurity
from .tools import CampusApiClient


def create_app(
    settings: Settings | None = None,
    api_client: CampusApiClient | None = None,
    llm_interpreter: LlmInterpreter | None = None,
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
    active_llm_interpreter = None
    if active_settings.effective_mode == "llm":
        active_llm_interpreter = llm_interpreter or LlmInterpreter(active_settings)
    # 分类建议的 LLM 兜底独立于主 mode：只要配了 key 就可用（规则未命中时兜底），
    # 便于 rules 模式下也能智能建议分类。llm 模式复用 active_llm_interpreter 同一实例。
    classify_interpreter = None
    if active_settings.lost_found_llm_api_key.strip():
        classify_interpreter = active_llm_interpreter or llm_interpreter or LlmInterpreter(active_settings)
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
            if active_llm_interpreter:
                await active_llm_interpreter.close()
            if classify_interpreter and classify_interpreter is not active_llm_interpreter:
                await classify_interpreter.close()

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
            "status": "llm_ready" if active_settings.effective_mode == "llm" else "rules_ready",
            "capabilities": {
                "domains": ["lost_and_found"],
                "actions": [
                    "report_lost",
                    "report_found",
                    "search_found_items",
                    "search_lost_items",
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
            interpretation = None
            if active_llm_interpreter and not (payload.confirmed or payload.confirmation_id):
                try:
                    interpretation = await interpret_with_retry(
                        active_llm_interpreter,
                        payload.message,
                        payload.conversation_context.shared_data,
                    )
                except LlmUnavailable:
                    if active_settings.llm_fail_closed:
                        # fail-closed（默认）：LLM 不可用/输出不可信 → 显式失败，不降级规则
                        event_store.append(
                            request_id,
                            AgentEvent(
                                "model_error",
                                {"reason": "model_unavailable_or_invalid", "mode": "fail_closed"},
                            ),
                        )
                        return InvokeResponse(
                            response="智能识别服务（llm）暂时不可用，请稍后重试。",
                            status="failed",
                            request_id=request_id,
                        )
                    # 旧行为（降级规则引擎）：仅当 llm_fail_closed=false 时生效
                    event_store.append(
                        request_id,
                        AgentEvent(
                            "model_fallback",
                            {"reason": "model_unavailable_or_invalid", "mode": "rules"},
                        ),
                    )
            response = await rule_engine.handle(
                payload,
                verified,
                request_id,
                lambda event: event_store.append(request_id, event),
                interpreted_intent=interpretation.intent if interpretation else None,
                interpreted_fields=(
                    interpretation.fields.model_dump(exclude_none=True) if interpretation else None
                ),
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

    @app.post("/agent/classify", response_model=ClassifyResponse)
    async def classify(payload: ClassifyRequest, request: Request) -> ClassifyResponse:
        """物品名 → 分类建议。规则优先；未命中且配置了 LLM 时兜底。

        fail-open：LLM 出错/不确定一律返回 category=None（200），绝不 5xx，
        因为分类建议只是表单预填，阻塞创建报告不可接受。
        """
        await security.verify(request, "classify")
        category = map_category(payload.item_name)
        if category is None and classify_interpreter is not None:
            try:
                suggestion = await classify_interpreter.classify_item(payload.item_name)
                category = suggestion.category
            except LlmUnavailable:
                category = None
        return ClassifyResponse(category=category)

    return app


app = create_app()
