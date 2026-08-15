"""REST / MCP 共用的 L&F invoke 主流程（chat-memory-requirements §7.1）。

此前 REST 入口（``main.py``）与 MCP 入口（``mcp_servers.lost_found_server``）各自复制
一套 LLM 解释 + 规则引擎 + fail-closed 逻辑。本次改造把主流程收敛到
``LostFoundInvokeService.handle_invoke``，两个入口只负责：取身份、限流、事件流挂接、
以及按链路差异传参（面板链路注入 recent_messages + 在线单次尝试；MCP 链路不注入完整
历史 + 重试 3 次）。

记忆是增强而非主流程依赖：``build_context`` / ``persist_turn`` 内部全部 best-effort，
任何记忆读写失败都降级为无记忆执行，不阻断搜索/报失/报拾/认领（§4 设计原则）。
"""

import logging
from collections.abc import Callable
from typing import Any

from .confirmation import PendingConfirmation
from .events import AgentEvent
from .llm import LlmInterpreter, LlmUnavailable, interpret_with_retry
from .memory import MemoryManager
from .models import InvokeRequest, InvokeResponse
from .rules import RuleEngine, detect_language
from .security import VerifiedRequest
from .config import Settings

logger = logging.getLogger(__name__)

Emit = Callable[[AgentEvent], None]


class LostFoundInvokeService:
    """REST 8083 与 MCP 8085 共用的 invoke 主流程。"""

    def __init__(
        self,
        *,
        settings: Settings,
        rule_engine: RuleEngine,
        memory_manager: MemoryManager,
        llm_interpreter: LlmInterpreter | None = None,
    ) -> None:
        self._settings = settings
        self._rules = rule_engine
        self._memory = memory_manager
        self._llm = llm_interpreter

    async def handle_invoke(
        self,
        payload: InvokeRequest,
        verified: VerifiedRequest,
        request_id: str,
        *,
        emit: Emit | None = None,
        interpret_attempts: int = 1,
        include_recent_messages: bool = True,
    ) -> InvokeResponse:
        """处理一条 invoke。

        :param interpret_attempts: LLM 失败重试次数。面板在线请求传 1（模型超时后须在
            Web 超时前降级）；MCP 链路可传 3（§7.6 历史行为）。
        :param include_recent_messages: 面板链路为 True（注入 recent_messages）；MCP 链路为
            False（orchestration 已有 MemorySaver，不重复注入完整历史，§7.6）。
        """
        active_emit = emit or (lambda event: None)
        session_id = payload.conversation_context.session_id or request_id

        # 1) 加载记忆（会话摘要 + recent messages + 用户事实 + pending 草稿），降级为空记忆
        memory_ctx = await self._memory.build_context(
            verified,
            session_id,
            include_recent_messages=include_recent_messages,
        )

        # 2) 确认轮：在规则引擎消费之前只读探测 pending，供 invoke 后抽取事实
        pre_pending: PendingConfirmation | None = None
        if (payload.confirmed or payload.confirmation_id) and payload.confirmation_id:
            pre_pending = self._memory.peek_pending(payload.confirmation_id)

        # 3) LLM 意图/字段解释（确认轮不解释；记忆已注入，提升跨轮意图与字段提取）
        interpretation = None
        if self._llm is not None and not (payload.confirmed or payload.confirmation_id):
            try:
                interpretation = await interpret_with_retry(
                    self._llm,
                    payload.message,
                    payload.conversation_context.shared_data,
                    memory_context=memory_ctx,
                    attempts=interpret_attempts,
                )
            except LlmUnavailable:
                if self._settings.llm_fail_closed:
                    active_emit(
                        AgentEvent(
                            "model_error",
                            {"reason": "model_unavailable_or_invalid", "mode": "fail_closed"},
                        )
                    )
                    return InvokeResponse(
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
                active_emit(
                    AgentEvent(
                        "model_fallback",
                        {"reason": "model_unavailable_or_invalid", "mode": "rules"},
                    )
                )

        # 4) 规则引擎主流程（记忆参与缺字段补全 / 候选偏置）
        try:
            response = await self._rules.handle(
                payload,
                verified,
                request_id,
                active_emit,
                interpreted_intent=interpretation.intent if interpretation else None,
                interpreted_fields=(
                    interpretation.fields.model_dump(exclude_none=True)
                    if interpretation
                    else None
                ),
                memory_context=memory_ctx,
            )
        except Exception:
            logger.exception("L&F invoke internal error: request_id=%s", request_id)
            response = InvokeResponse(
                response=(
                    "Agent 处理请求时发生内部错误。"
                    if detect_language(payload.message) == "zh"
                    else "The agent encountered an internal error while processing your request."
                ),
                status="failed",
                request_id=request_id,
            )
            active_emit(
                AgentEvent(
                    "agent_error",
                    {"code": "INTERNAL_ERROR", "message": response.response},
                )
            )

        # 5) invoke 后持久化（消息 + 事实 + pending 草稿 + 摘要滚动），best-effort
        await self._memory.persist_turn(
            verified=verified,
            session_id=session_id,
            request_id=request_id,
            payload=payload,
            interpretation=interpretation,
            response=response,
            pre_pending=pre_pending,
            memory_context=memory_ctx,
        )
        return response
