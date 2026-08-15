"""Agent 持久记忆：会话历史 + 用户长期事实（chat-memory-requirements §7.1）。

模块组成
--------
``MemoryClient``
    封装后端记忆内部 API（复用 ``CampusApiClient`` 的 delegation token + 错误语义），
    并把后端 camelCase JSON 归一化为 snake_case 便于 agent 侧消费。失败自动降级：
    进入冷却窗口后跳过后续请求，绝不阻断主流程。

``MemoryManager``
    编排：build_context（invoke 前加载会话/事实/pending 并注入 LLM 与规则引擎）、
    persist_turn（invoke 后写消息 + 事实 + 摘要滚动 + pending 草稿同步）、
    _roll_summary（超过阈值把旧原文折叠为摘要，LLM 不可用则规则截断降级）。

``FactExtractor``
    从确认成功的写操作 payload 结构化抽取长期事实（不额外调 LLM）。

记忆是增强而非主流程依赖：任何读写失败都降级为空记忆，不影响搜索/报失/报拾/认领。
"""

import logging
import re
import time
from collections.abc import Callable
from typing import Any

from .confirmation import ConfirmationStore, PendingConfirmation
from .llm import LlmInterpreter, LlmUnavailable
from .tools import BackendApiError, CampusApiClient

logger = logging.getLogger(__name__)

_CONFIRM_ACTIONS = ("report_lost", "report_found", "claim_item")

# 事实写入时只透传这些键（payload 里的 images/visual_* 等不落库）
_FACT_KEYS = (
    "fact_type",
    "item_name",
    "category",
    "colour",
    "location",
    "event_date",
    "time_description",
    "status",
    "confidence",
    "session_id",
)

# 从 shared_context 提取"结构化抽取"时保留的字段（避免把指纹/图片数组塞进 extracted_fields）
_FACT_FIELD_KEYS = (
    "item_name",
    "category",
    "description",
    "colour",
    "location",
    "event_date",
    "time_description",
    "keyword",
    "report_id",
    "proof_description",
)


def _camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def _normalize(value: Any) -> Any:
    """后端 camelCase JSON → agent 侧 snake_case（递归）。"""
    if isinstance(value, dict):
        return {_camel_to_snake(key): _normalize(val) for key, val in value.items()}
    if isinstance(value, list):
        return [_normalize(item) for item in value]
    return value


class MemoryClient:
    """后端记忆内部 API 的 agent 侧客户端。

    带冷却式降级：一次失败后进入 cooldown 窗口，窗口内跳过请求并返回默认值
    （None / [] / {}），避免连接故障时每次 invoke 都打一次后端、刷爆日志。
    """

    def __init__(
        self,
        api_client: CampusApiClient,
        *,
        cooldown_seconds: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._api = api_client
        self._cooldown = cooldown_seconds
        self._clock = clock
        self._degraded_until = 0.0

    @property
    def degraded(self) -> bool:
        """当前是否处于降级窗口（跳过记忆读写）。"""
        return self._clock() < self._degraded_until

    async def get_session(self, verified, session_id: str) -> dict[str, Any] | None:
        """取会话（含 messages/summary/pending_confirmation）；不存在或降级返回 None。"""

        async def _run() -> dict[str, Any] | None:
            try:
                raw = await self._api.memory_get_session(
                    verified.user_id, verified.user_role, session_id
                )
            except BackendApiError as exc:
                if exc.status_code == 404:
                    return None  # 会话尚不存在，正常
                raise
            return _normalize(raw) if raw else None

        return await self._call(_run, None)

    async def upsert_session(
        self,
        verified,
        session_id: str,
        *,
        title: str | None = None,
        summary: str | None = None,
        pending_confirmation: dict[str, Any] | None = None,
        clear_pending_confirmation: bool = False,
    ) -> dict[str, Any] | None:
        """建/更新会话。pending 非破坏性：不传则不触碰已有草稿（§7.5）。"""

        async def _run() -> dict[str, Any] | None:
            body: dict[str, Any] = {"sessionId": session_id}
            if title is not None:
                body["title"] = title
            if summary is not None:
                body["summary"] = summary
            if pending_confirmation is not None:
                body["pendingConfirmation"] = pending_confirmation
            if clear_pending_confirmation:
                body["clearPendingConfirmation"] = True
            raw = await self._api.memory_upsert_session(
                verified.user_id, verified.user_role, body
            )
            return _normalize(raw) if raw else None

        return await self._call(_run, None)

    async def append_message(
        self,
        verified,
        session_id: str,
        role: str,
        text: str,
        *,
        intent: str | None = None,
        extracted_fields: dict[str, Any] | None = None,
        image_object_keys: list[str] | None = None,
        trace_id: str | None = None,
    ) -> dict[str, Any] | None:
        """追加一条会话消息（role ∈ USER/AGENT）。"""

        async def _run() -> dict[str, Any] | None:
            body: dict[str, Any] = {"role": role, "messageText": text}
            if intent is not None:
                body["intent"] = intent
            if extracted_fields is not None:
                body["extractedFields"] = extracted_fields
            if image_object_keys:
                body["imageObjectKeys"] = image_object_keys
            if trace_id:
                body["traceId"] = trace_id
            raw = await self._api.memory_append_message(
                verified.user_id, verified.user_role, session_id, body
            )
            return _normalize(raw) if raw else None

        return await self._call(_run, None)

    async def prune_messages(
        self, verified, session_id: str, keep_latest: int
    ) -> dict[str, Any] | None:
        """摘要滚动后裁剪原文：仅保留最近 keep_latest 条。"""

        async def _run() -> dict[str, Any] | None:
            raw = await self._api.memory_prune_messages(
                verified.user_id, verified.user_role, session_id, keep_latest
            )
            return _normalize(raw) if raw else None

        return await self._call(_run, None)

    async def get_user_facts(self, verified) -> list[dict[str, Any]]:
        """取当前用户长期事实列表（按 updated_at 倒序）；降级返回 []。"""

        async def _run() -> list[dict[str, Any]]:
            raw = await self._api.memory_get_user_facts(verified.user_id, verified.user_role)
            data = _normalize(raw) if raw else {}
            facts = data.get("facts") or []
            return facts if isinstance(facts, list) else []

        return await self._call(_run, [])

    async def upsert_fact(self, verified, fact: dict[str, Any]) -> dict[str, Any] | None:
        """写入/合并一条用户级事实（camelCase 序列化，只透传 _FACT_KEYS）。"""

        async def _run() -> dict[str, Any] | None:
            body = {
                "factType": fact.get("fact_type"),
                "itemName": fact.get("item_name"),
                "category": fact.get("category"),
                "colour": fact.get("colour"),
                "location": fact.get("location"),
                "eventDate": fact.get("event_date"),
                "timeDescription": fact.get("time_description"),
                "status": fact.get("status"),
                "confidence": fact.get("confidence"),
                "sessionId": fact.get("session_id"),
            }
            body = {key: value for key, value in body.items() if value is not None}
            raw = await self._api.memory_upsert_fact(verified.user_id, verified.user_role, body)
            return _normalize(raw) if raw else None

        return await self._call(_run, None)

    async def _call(self, func, default):
        if self.degraded:
            return default
        try:
            return await func()
        except Exception as exc:  # 记忆是增强：任何失败都降级，不阻断主流程
            self._degraded_until = self._clock() + self._cooldown
            logger.warning("L&F memory backend degraded (falling back to no-memory): %s", exc)
            return default


class FactExtractor:
    """从确认成功的写操作 payload 抽取长期事实（不额外调 LLM，§7.3）。"""

    @staticmethod
    def item_fact(action: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        """报失/报拾创建成功 → LOST_ITEM / FOUND_ITEM 事实。"""
        if action not in ("report_lost", "report_found"):
            return None
        item_name = payload.get("item_name")
        category = payload.get("category")
        if not item_name or not category:
            return None
        event_date = payload.get("event_date")
        fact: dict[str, Any] = {
            "fact_type": "LOST_ITEM" if action == "report_lost" else "FOUND_ITEM",
            "item_name": str(item_name),
            "category": str(category),
            "colour": payload.get("colour"),
            "location": payload.get("location"),
            "event_date": event_date.isoformat() if hasattr(event_date, "isoformat") else event_date,
            "time_description": payload.get("time_description"),
            "status": "OPEN",
            "confidence": 1.0,
        }
        return {key: value for key, value in fact.items() if value is not None}


class MemoryManager:
    """编排：加载 → 注入 → 保存 → 裁剪（§7.1）。"""

    def __init__(
        self,
        client: MemoryClient,
        confirmations: ConfirmationStore,
        *,
        llm_interpreter: LlmInterpreter | None = None,
        max_recent_messages: int = 12,
        summary_max_length: int = 800,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._client = client
        self._confirmations = confirmations
        self._llm = llm_interpreter
        self.max_recent_messages = max_recent_messages
        self.summary_max_length = summary_max_length
        self._clock = clock

    async def build_context(
        self,
        verified,
        session_id: str,
        *,
        include_recent_messages: bool = True,
    ) -> dict[str, Any]:
        """invoke 前加载记忆上下文，注入 LLM 与规则引擎（§7.2）。

        返回 {session_summary, recent_messages, user_facts, pending_confirmation}。
        任何读取失败都由 MemoryClient 降级为空值；会话不存在按空会话处理。
        """
        session = await self._client.get_session(verified, session_id)
        facts = await self._client.get_user_facts(verified)
        summary = ""
        messages: list[dict[str, Any]] = []
        pending: dict[str, Any] | None = None
        if session:
            summary = session.get("summary") or ""
            messages = session.get("messages") or []
            pending = session.get("pending_confirmation")
            if isinstance(pending, dict):
                self._restore_pending(verified, pending)
            else:
                pending = None
        recent: list[dict[str, str]] = []
        if include_recent_messages and messages:
            recent = [
                {
                    "role": str(m.get("role") or "user").lower(),
                    "content": m.get("message_text") or "",
                }
                for m in messages[-self.max_recent_messages :]
            ]
        return {
            "session_summary": summary,
            "recent_messages": recent,
            "user_facts": facts if isinstance(facts, list) else [],
            "pending_confirmation": pending,
        }

    def peek_pending(
        self, confirmation_id: str | None
    ) -> PendingConfirmation | None:
        """确认轮 handle 之前只读探测 pending（供事实抽取；不消耗）。

        必须在 rule_engine.handle 消费确认之前调用，否则返回 None。
        """
        if not confirmation_id:
            return None
        return self._confirmations.get(confirmation_id)

    async def persist_turn(
        self,
        verified,
        session_id: str,
        request_id: str,
        payload,
        interpretation,
        response,
        pre_pending: PendingConfirmation | None,
        memory_context: dict[str, Any],
    ) -> None:
        """invoke 结束后同步写消息 + 事实 + pending 草稿 + 摘要滚动。

        全部 best-effort：任一失败只降级记忆，不改变已返回给用户的响应。
        """
        try:
            clear_pending = self._should_clear_pending(payload, response, memory_context)
            pending_json = self._pending_json(
                verified, session_id, payload, response
            )
            await self._client.upsert_session(
                verified,
                session_id,
                title=None,
                summary=memory_context.get("session_summary") or None,
                pending_confirmation=pending_json,
                clear_pending_confirmation=clear_pending,
            )

            intent, fields = self._user_intent_and_fields(payload, interpretation, response)
            await self._client.append_message(
                verified,
                session_id,
                "USER",
                payload.message,
                intent=intent,
                extracted_fields=fields,
                image_object_keys=[
                    image.object_key for image in payload.images if image.object_key
                ],
                trace_id=request_id,
            )
            await self._client.append_message(
                verified,
                session_id,
                "AGENT",
                response.response if response else "",
                intent=None,
                extracted_fields=None,
                image_object_keys=None,
                trace_id=request_id,
            )

            await self._persist_facts(verified, session_id, response, pre_pending)

            await self._roll_summary(verified, session_id)
        except Exception as exc:
            logger.warning(
                "L&F memory persist degraded (request_id=%s): %.200s", request_id, exc
            )

    # ───────────────────────── 事实抽取 ─────────────────────────

    async def _persist_facts(
        self,
        verified,
        session_id: str,
        response,
        pre_pending: PendingConfirmation | None,
    ) -> None:
        if response is None or response.status not in ("completed", "match_found"):
            return
        if pre_pending is None or pre_pending.action not in ("report_lost", "report_found"):
            return
        fact = FactExtractor.item_fact(pre_pending.action, pre_pending.payload)
        if fact is None:
            return
        fact["session_id"] = session_id
        await self._client.upsert_fact(verified, fact)
        await self._maybe_write_location_fact(verified, fact, session_id)

    async def _maybe_write_location_fact(
        self, verified, item_fact: dict[str, Any], session_id: str
    ) -> None:
        """location 在用户事实中出现 ≥2 次时沉淀为 LOCATION 事实（低频不写，§7.3/§12）。"""
        location = item_fact.get("location")
        if not location:
            return
        facts = await self._client.get_user_facts(verified)
        locations = [f.get("location") for f in facts if f.get("location")]
        if locations.count(location) >= 2:
            await self._client.upsert_fact(
                verified,
                {
                    "fact_type": "LOCATION",
                    "location": location,
                    "session_id": session_id,
                    "confidence": 0.7,
                },
            )

    # ───────────────────────── 摘要滚动（§7.4）─────────────────────────

    async def _roll_summary(self, verified, session_id: str) -> None:
        session = await self._client.get_session(verified, session_id)
        if session is None:
            return
        messages = session.get("messages") or []
        if len(messages) <= self.max_recent_messages:
            return
        fold = messages[: len(messages) - self.max_recent_messages]
        summary = await self._summarize(session.get("summary"), fold)
        await self._client.upsert_session(
            verified, session_id, summary=summary or None
        )
        await self._client.prune_messages(
            verified, session_id, keep_latest=self.max_recent_messages
        )

    async def _summarize(
        self, existing_summary: str | None, fold_messages: list[dict[str, Any]]
    ) -> str:
        """LLM 摘要；不可用时规则截断降级（功能不失效，§7.4）。"""
        if self._llm is not None:
            try:
                turns = [
                    {
                        "role": str(m.get("role") or "user").lower(),
                        "content": m.get("message_text") or "",
                    }
                    for m in fold_messages
                ]
                text = await self._llm.summarize_history(turns, existing_summary)
                if text and text.strip():
                    return self._truncate(text, self.summary_max_length)
            except LlmUnavailable:
                pass
        parts = [existing_summary] if existing_summary else []
        parts.extend(
            f"[{str(m.get('role') or 'user').lower()}] {m.get('message_text')}"
            for m in fold_messages
        )
        return self._truncate(" | ".join(part for part in parts if part), self.summary_max_length)

    @staticmethod
    def _truncate(text: str, limit: int) -> str:
        if len(text) <= limit:
            return text
        return text[: limit - 1].rstrip() + "…"

    # ───────────────────────── pending 草稿（§7.5）─────────────────────────

    def _restore_pending(self, verified, pending: dict[str, Any]) -> None:
        """把从会话恢复的未过期 pending 重新 seed 回进程内 store（跨进程可用）。"""
        try:
            confirmation_id = pending.get("confirmation_id")
            action = pending.get("action")
            expires_at = pending.get("expires_at")
            if (
                not confirmation_id
                or action not in _CONFIRM_ACTIONS
                or not isinstance(expires_at, (int, float))
            ):
                return
            restored = PendingConfirmation(
                user_id=str(pending.get("user_id") or verified.user_id),
                action=action,
                payload=dict(pending.get("payload") or {}),
                expires_at=float(expires_at),
                session_id=pending.get("session_id"),
                created_at=float(pending.get("created_at") or 0.0),
                role=pending.get("role"),
            )
            self._confirmations.restore(confirmation_id, restored)
        except Exception as exc:  # 恢复失败不影响主流程
            logger.warning("L&F memory restore pending degraded: %.200s", exc)

    def _pending_json(
        self,
        verified,
        session_id: str,
        payload,
        response,
    ) -> dict[str, Any] | None:
        """本轮生成新确认时，构造待持久化的 pending JSON；否则 None。"""
        if response is None or response.confirmation_required is None:
            return None
        confirmation_id = response.confirmation_required.confirmation_id
        pending = self._confirmations.get(confirmation_id)
        if pending is None:
            return None
        return {
            "confirmation_id": confirmation_id,
            "action": pending.action,
            "payload": pending.payload,
            "created_at": pending.created_at,
            "expires_at": pending.expires_at,
            "user_id": verified.user_id,
            "session_id": session_id,
            "role": pending.role or verified.user_role,
        }

    def _should_clear_pending(
        self,
        payload,
        response,
        memory_context: dict[str, Any],
    ) -> bool:
        """确认请求（已消费/拒绝）或已有草稿过期 → 清除 DB 中的 pending。"""
        if payload.confirmed or payload.confirmation_id:
            return True
        pending = memory_context.get("pending_confirmation")
        if isinstance(pending, dict):
            expires_at = pending.get("expires_at")
            if isinstance(expires_at, (int, float)) and expires_at <= self._clock():
                return True
        return False

    @staticmethod
    def _user_intent_and_fields(
        payload, interpretation, response
    ) -> tuple[str | None, dict[str, Any] | None]:
        """用户消息的 intent 与结构化抽取字段（优先 LLM 结果，否则取 shared_context）。"""
        if interpretation is not None:
            return interpretation.intent, interpretation.fields.model_dump(exclude_none=True)
        context = (response.shared_context if response else None) or {}
        intent = context.get("intent")
        fields = {
            key: context[key] for key in _FACT_FIELD_KEYS if context.get(key) is not None
        }
        return (str(intent) if intent else None, fields or None)
