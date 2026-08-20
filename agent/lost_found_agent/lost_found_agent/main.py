"""Lost & Found Agent HTTP 入口：FastAPI 应用工厂 + 对外 HTTP 路由。

本模块是整个失物招领 Agent 的对外 HTTP 门面，把 Web 端 / chat-core 的请求转换成
Agent 内部能力（规则引擎、LLM 意图识别、Embedding 匹配、后端 Campus API 调用）。

主要职责：
- create_app()：应用工厂函数，组装依赖（安全校验、限流、事件存储、Campus 客户端、
  Embedding 客户端、LLM 解释器、规则引擎），并注册六条 HTTP 路由：
  * GET  /health              健康检查（含运行模式、模型是否已配置）；
  * GET  /agent/capabilities  能力描述（支持的领域、动作、语言、确认要求）；
  * POST /agent/invoke        核心聊天入口：LLM 意图识别 + 规则引擎执行，支持
    确认流程、LLM 降级、fail-closed，并把过程事件写入事件存储供 SSE 拉取；
  * GET  /agent/stream        SSE 事件流：按 request_id 回放本次请求产生的事件；
  * POST /agent/classify      物品名 → 分类建议（规则优先，LLM 兜底，fail-open）；
  * POST /agent/search        Browse 以图搜物：不经聊天/LLM，直接检索打分。
- 应用级生命周期(lifespan)：优雅停机时统一释放所有对外客户端连接。
- 模块末尾 `app = create_app()` 创建全局单例，供 uvicorn 以 app:app 启动时引用。
"""

# --- 标准库导入 ---
from collections.abc import AsyncIterator  # 类型标注：异步生成器的返回类型（lifespan）
from contextlib import asynccontextmanager  # 把异步生成器包装成语境管理器（FastAPI lifespan）
from typing import Any
from uuid import uuid4  # 生成全局唯一的 request_id 兜底值

# --- 第三方导入 ---
from fastapi import FastAPI, Request  # 应用对象与原始请求对象
from fastapi.responses import StreamingResponse  # SSE 流式响应类型

# --- 本包内部导入 ---
from .config import Settings, get_settings  # 配置模型与进程内单例获取
from .confirmation import ConfirmationStore  # 确认流程：创建/校验确认令牌的存储
from .events import AgentEvent, EventStore  # 事件模型与事件存储（SSE 的数据源）
from .llm import LlmInterpreter, LlmUnavailable, interpret_with_retry  # LLM 意图识别与重试
from .models import (
    ClassifyRequest,   # 分类建议请求模型（物品名 → 分类）
    ClassifyResponse,  # 分类建议响应模型
    InvokeRequest,     # 聊天调用请求模型（消息/会话上下文/确认标记/图片）
    InvokeResponse,    # 聊天调用响应模型（状态/匹配结果/确认信息/动作）
    SearchRequest,     # 以图搜物请求模型（图 + 可选筛选条件）
    SearchResponse,    # 以图搜物响应模型（状态 + 匹配结果）
)
from .pretrained import PretrainedEmbeddingClient  # 预训练嵌入客户端（图片/文本向量化）
from .rate_limit import RateLimiter  # 单实例内存限流器（按用户/会话）
from .rules import RuleEngine, detect_language, map_category, search_candidates
# RuleEngine: 规则引擎（意图→工具调度、确认流程、工具白名单）；
# detect_language: 检测消息为中文还是英文（决定提示文案语言）；
# map_category: 物品名 → 分类枚举；search_candidates: 候选检索与打分链路
from .security import AgentSecurity  # 入站安全校验（签名/JWT/时间窗口/nonce 防重放）
from .tools import BackendApiError, CampusApiClient  # 后端 Campus API 客户端及其异常


def create_app(
    settings: Settings | None = None,
    api_client: CampusApiClient | None = None,
    llm_interpreter: LlmInterpreter | None = None,
) -> FastAPI:
    """创建失物招领 Agent 的 FastAPI 应用（工厂函数）。

    入参（均为可选注入，便于测试时替换真实依赖）：
    - settings: 配置；不传则用 get_settings() 的全局单例；
    - api_client: Campus 后端客户端；不传则内部创建（该内部实例由本应用负责关闭）；
    - llm_interpreter: LLM 解释器；不传且模式为 llm 时内部创建。
    返回：装配完成的 FastAPI 实例（已注册路由与 lifespan）。
    调用场景：模块底部 `app = create_app()`，或测试中传入模拟依赖。
    """
    # 配置解析：未显式传入则使用全局单例（避免重复解析 .env）。
    active_settings = settings or get_settings()
    # 安全校验器：负责所有入站请求的签名 / JWT / nonce 校验。
    security = AgentSecurity(active_settings)
    # 限流器：按 用户(分钟级) + 会话(累计) 双重限流，防异常调用。
    limiter = RateLimiter(
        active_settings.agent_rate_limit_per_minute,
        active_settings.agent_rate_limit_per_session,
    )
    # 事件存储：记录每个 request_id 的生命周期事件，供 /agent/stream 拉取推送。
    event_store = EventStore(active_settings.agent_event_ttl_seconds)
    # 记录 api_client 是否外部注入：仅当是内部创建时，lifespan 关闭阶段才需要负责关闭。
    owns_api_client = api_client is None
    active_api_client = api_client or CampusApiClient(active_settings)
    # 嵌入客户端：以图搜物/图文匹配时把图片、文本向量化（可对接本地嵌入服务）。
    embedding_client = PretrainedEmbeddingClient(active_settings)
    # 主 LLM 解释器：仅当生效模式为 llm 时创建（invoke 路由的意图识别使用）。
    active_llm_interpreter = None
    if active_settings.effective_mode == "llm":
        active_llm_interpreter = llm_interpreter or LlmInterpreter(active_settings)
    # 分类建议的 LLM 兜底独立于主 mode：只要配了 key 就可用（规则未命中时兜底），
    # 便于 rules 模式下也能智能建议分类。llm 模式复用 active_llm_interpreter 同一实例。
    classify_interpreter = None
    if active_settings.lost_found_llm_api_key.strip():
        classify_interpreter = (
            active_llm_interpreter or llm_interpreter or LlmInterpreter(active_settings)
        )
    # 规则引擎：核心执行体，负责"意图 → 工具调度、确认流程、候选匹配打分"。
    # ConfirmationStore(ttl=600)：确认令牌 10 分钟内有效。
    rule_engine = RuleEngine(
        active_api_client,
        ConfirmationStore(ttl_seconds=600),
        active_settings.lost_found_match_min_score,
        embedding_client,
    )

    # lifespan 上下文管理器：应用启动时进入，停止时执行清理，实现优雅停机。
    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            yield  # yield 之前可放启动逻辑（当前没有），之后进入关闭逻辑
        finally:
            # 关闭顺序：先关外部依赖，最后关嵌入客户端，避免资源泄漏。
            if owns_api_client:
                # 仅当客户端是本工厂内部创建时才负责关闭，避免误关外部注入的客户端。
                await active_api_client.close()
            if active_llm_interpreter:
                await active_llm_interpreter.close()
            # classify_interpreter 可能与主解释器是同一个实例，判断引用避免重复关闭。
            if classify_interpreter and classify_interpreter is not active_llm_interpreter:
                await classify_interpreter.close()
            await embedding_client.close()

    # 组装 FastAPI 应用：标题/版本写入 OpenAPI 文档，注册 lifespan。
    app = FastAPI(
        title="CampusLink Lost & Found Agent",
        version=active_settings.agent_version,
        lifespan=lifespan,
    )

    @app.get("/health")
    async def health() -> dict[str, object]:
        """健康检查端点：返回服务存活状态、运行模式与模型配置情况。

        供运维探活 / 负载均衡健康探测使用。mode 为实际生效模式（rules/llm）；
        model_configured 表示是否配置了非空 LLM key，供上层判断是否可用 LLM 能力。
        入参：无。返回：描述服务状态的 JSON 字典。
        """
        return {
            "status": "ok",
            "service": active_settings.agent_name,
            "version": active_settings.agent_version,
            "mode": active_settings.effective_mode,
            # 去掉首尾空白后再判断是否非空，避免全空格 key 被误判为"已配置"。
            "model_configured": bool(active_settings.lost_found_llm_api_key.strip()),
        }

    @app.get("/agent/capabilities")
    async def capabilities() -> dict[str, object]:
        """能力描述端点：向调用方声明 Agent 支持的领域、动作、语言与确认要求。

        供 chat-core / Web 端动态发现 Agent 能力。status 区分 llm_ready 与 rules_ready；
        write_confirmation_required 表示写操作（上报/认领）一律需要用户二次确认。
        入参：无。返回：能力描述 JSON 字典。
        """
        return {
            "agent": active_settings.agent_name,
            "version": active_settings.agent_version,
            "status": "llm_ready" if active_settings.effective_mode == "llm" else "rules_ready",
            "capabilities": {
                "domains": ["lost_and_found"],
                "actions": [
                    "report_lost",         # 上报丢失
                    "report_found",        # 上报拾得
                    "search_found_items",  # 搜索拾得物（失主找物）
                    "search_lost_items",   # 搜索丢失物（拾得者找失主）
                    "get_item_detail",     # 查看物品详情
                    "claim_item",          # 认领物品
                ],
                "languages": ["zh", "en"],
                "write_confirmation_required": True,  # 写操作必须经确认流程
            },
        }

    @app.post("/agent/invoke", response_model=InvokeResponse)
    async def invoke(payload: InvokeRequest, request: Request) -> InvokeResponse:
        """核心聊天入口：接收用户消息，经 LLM 意图识别 + 规则引擎执行后返回结果。

        入参：
        - payload: InvokeRequest（含 message、会话上下文、确认标记、确认 id、trace、图片）；
        - request: FastAPI 原始请求（用于入站安全校验）。
        返回：InvokeResponse（含状态、文案、匹配结果、确认信息、已执行动作）。

        流程概要：
        1) 安全校验 → 生成/继承 request_id 与会话 id → 限流 → 记录 agent_start 事件；
        2) 尚未确认且配置了 LLM 时，先用 LLM 做一次意图识别（只尝试一次）；LLM 不可用
           时按 llm_fail_closed 决定是显式失败(fail-closed)还是降级到规则引擎(fail-open)；
        3) 调用 rule_engine.handle 执行完整链路（工具调用/确认流程/候选匹配），并把过程
           事件实时写入 event_store，供 /agent/stream 以 SSE 推送给前端；
        4) 兜底捕获所有未预期异常：返回固定文案的 failed 响应并记录 agent_error 事件，
           保证任何内部错误都不会以 5xx 泄漏给调用方；
        5) 无论成败都记录 agent_done 事件，最后返回响应。
        """
        # 第一步：入站安全校验（时间窗口/HMAC 签名/JWT 令牌/nonce 防重放），失败抛 401/403。
        verified = await security.verify(request, "invoke")
        # request_id 优先级：调用方 trace_parent 里的 trace_id > 校验出的 trace_id > 随机生成。
        request_id = payload.trace_parent.trace_id or verified.trace_id or str(uuid4())
        # 会话 id：沿用调用方声明的 session_id，缺省退化为 request_id（一次性会话）。
        session_id = payload.conversation_context.session_id or request_id
        # 限流：按 用户+分钟 与 会话累计 双重检查，超限抛 429。
        limiter.check(verified.user_id, session_id)
        # 记录本次请求开始事件，SSE 订阅方据此感知"已开始处理"。
        event_store.append(
            request_id,
            AgentEvent(
                "agent_start", {"agent": active_settings.agent_name, "message": payload.message}
            ),
        )
        try:
            # LLM 意图识别：仅在"配置了 LLM 且本次尚未确认"时触发——已确认的请求
            # 直接复用确认时已解析出的意图，不再重复调用模型（省时省钱）。
            interpretation = None
            if active_llm_interpreter and not (payload.confirmed or payload.confirmation_id):
                try:
                    interpretation = await interpret_with_retry(
                        active_llm_interpreter,
                        payload.message,
                        payload.conversation_context.shared_data,
                        # 在线请求只尝试一次，确保模型超时后能在 Web 超时前降级。
                        attempts=1,
                    )
                except LlmUnavailable:
                    if active_settings.llm_fail_closed:
                        # 可选 fail-closed：LLM 不可用/输出不可信 → 显式失败。
                        event_store.append(
                            request_id,
                            AgentEvent(
                                "model_error",
                                {"reason": "model_unavailable_or_invalid", "mode": "fail_closed"},
                            ),
                        )
                        return InvokeResponse(
                            # 根据消息语言返回对应的中文/英文失败文案，不再尝试执行。
                            response=(
                                "智能识别服务（llm）暂时不可用，请稍后重试。"
                                if detect_language(payload.message) == "zh"
                                else (
                                    "The AI interpretation service is temporarily "
                                    "unavailable. Please try again later."
                                )
                            ),
                            status="failed",
                            request_id=request_id,
                        )
                    # 默认行为：降级到同样受确认流程和工具白名单约束的规则引擎。
                    event_store.append(
                        request_id,
                        AgentEvent(
                            "model_fallback",
                            {"reason": "model_unavailable_or_invalid", "mode": "rules"},
                        ),
                    )
            # 调用规则引擎执行完整链路（工具调度/确认流程/候选匹配），并实时回传事件；
            # interpreted_intent / interpreted_fields 是 LLM 的解析结果，供规则引擎合并使用
            # （fields 只保留非 None 字段，避免空字段混入工具参数）。
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
            # 兜底异常处理：任何未预期的内部错误都不让请求变成 5xx，而是返回
            # 固定文案的 failed 响应，并把错误写入事件流供前端展示。
            response = InvokeResponse(
                response=(
                    "Agent 处理请求时发生内部错误。"
                    if detect_language(payload.message) == "zh"
                    else "The agent encountered an internal error while processing your request."
                ),
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
        # 无论成功失败都记录结束事件，SSE 客户端据此知道本次请求处理已结束。
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
        """SSE 端点：按 request_id 回放该请求产生的事件流。

        入参：
        - request_id: 查询参数，客户端发起 invoke 时拿到的请求 id；
        - request: FastAPI 原始请求（用于安全校验，要求令牌的 intended_action=stream）。
        返回：StreamingResponse，media_type=text/event-stream。
        调用场景：前端在 invoke 的同时（或之后）打开本端点，持续接收 agent_start、
        确认请求、工具调用、agent_done 等事件，逐步更新聊天界面；若该请求尚无事件，
        EventStore 会返回 NOT_FOUND 事件而非静默结束。
        """
        # 流式读取同样要求合法签名与令牌（intended_action=stream），防未授权拉取他人事件。
        await security.verify(request, "stream")
        # 把事件存储的生成器包装成 SSE 流式响应返回。
        return StreamingResponse(event_store.stream(request_id), media_type="text/event-stream")

    @app.post("/agent/classify", response_model=ClassifyResponse)
    async def classify(payload: ClassifyRequest, request: Request) -> ClassifyResponse:
        """物品名 → 分类建议。规则优先；未命中且配置了 LLM 时兜底。

        fail-open：LLM 出错/不确定一律返回 category=None（200），绝不 5xx，
        因为分类建议只是表单预填，阻塞创建报告不可接受。
        """
        # 安全校验（intended_action=classify），通过后进入轻量分类逻辑。
        await security.verify(request, "classify")
        # 先走规则：基于内置分类枚举匹配物品名（最快、零外呼）。
        category = map_category(payload.item_name)
        # 规则未命中 且 配置了 LLM key 时，才请求 LLM 兜底（/agent/classify 专用实例）。
        if category is None and classify_interpreter is not None:
            try:
                suggestion = await classify_interpreter.classify_item(payload.item_name)
                category = suggestion.category
            except LlmUnavailable:
                # fail-open：LLM 故障不抛错、不 5xx，返回 None（前端留空、用户可手动选择）。
                category = None
        return ClassifyResponse(category=category)

    @app.post("/agent/search", response_model=SearchResponse)
    async def agent_search(payload: SearchRequest, request: Request) -> SearchResponse:
        """Browse 以图搜物：按图 + 可选筛选检索候选并打分。

        不经聊天/LLM，直接复用 search_candidates 链路（与 Agent 面板同图结果一致）；
        language 固定 zh 使理由文案与面板一致；不注入 event_date，避免 ±30 天窗口兜底。

        入参：
        - payload: SearchRequest（必带图片，report_type 决定候选方向，其余筛选条件可选）；
        - request: FastAPI 原始请求（用于安全校验）。
        返回：SearchResponse（match_found 含匹配结果 / no_match / failed）。
        """
        # 安全校验（intended_action=search），通过后进入检索打分链路。
        verified = await security.verify(request, "search")
        request_id = verified.trace_id or str(uuid4())
        # 只放入非空字段：score_candidate 会把缺失字段按 str() 拼进 text 分量，
        # 若 key 以 None 存在会注入 "None" 幽灵文本（与 chat flow 的 query 构造一致）。
        # 因此用字典推导式把值为 None 的可选筛选条件过滤掉，构造干净的查询字典。
        query: dict[str, Any] = {
            field: value
            for field, value in (
                ("keyword", payload.keyword),
                ("category", payload.category),
                ("colour", payload.colour),
                ("location", payload.location),
                ("date_from", payload.date_from),
                ("date_to", payload.date_to),
            )
            if value is not None
        }
        # 提取图片级视觉指纹（预提取的图片指纹），用于基础匹配打分。
        fingerprints = [
            image.visual_fingerprint for image in payload.images if image.visual_fingerprint
        ]
        if fingerprints:
            query["visual_fingerprints"] = fingerprints
        # 提取图片级预训练向量（如已生成），用于深度语义 / 多模态匹配。
        pretrained = [image.visual_embedding for image in payload.images if image.visual_embedding]
        if pretrained:
            query["visual_embeddings"] = pretrained
        try:
            # 直接复用聊天流的核心检索打分链路 search_candidates：
            # target_report_type 决定候选方向（FOUND 视图搜 FOUND 候选，LOST 视图反之）；
            # embedding_client 负责对图片做预训练向量匹配；第二返回值 _action 本端点不需要。
            matches, _action = await search_candidates(
                active_api_client,
                query,
                verified,
                active_settings.lost_found_match_min_score,
                "zh",  # 固定中文，保证理由文案与 Agent 面板一致
                lambda event: None,  # 丢弃过程事件：本端点不暴露 SSE 流，无需记录
                target_report_type=payload.report_type,
                embedding_client=embedding_client,
            )
        except BackendApiError as exc:
            # 后端 Campus API 调用失败：返回结构化失败信息（含错误码），不抛 5xx。
            return SearchResponse(
                status="failed",
                request_id=request_id,
                message=f"Campus API ({exc.code}): {exc}",
            )
        # 有匹配返回 match_found（含匹配结果列表），否则返回 no_match。
        return SearchResponse(
            status="match_found" if matches else "no_match",
            match_results=matches,
            request_id=request_id,
        )

    return app


# 模块级全局应用实例：uvicorn 以 app:app 启动时引用，也可被测试直接 import。
app = create_app()
