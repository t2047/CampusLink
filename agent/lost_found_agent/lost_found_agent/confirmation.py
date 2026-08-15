"""写操作确认状态：与用户绑定、短期有效、一次性使用。

记忆改造（chat-memory-requirements §7.5）：pending 会随会话持久化到后端
（DB 为准、内存为缓存），因此 ``PendingConfirmation`` 增加 ``session_id`` /
``created_at`` / ``role`` 以支持跨进程恢复；``restore`` 用于重启后把会话中
未过期的确认重新 seed 回进程内 store。
"""

import secrets
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal


class ConfirmationError(ValueError):
    pass


@dataclass(frozen=True)
class PendingConfirmation:
    user_id: str
    action: Literal["report_lost", "report_found", "claim_item"]
    payload: dict[str, Any]
    expires_at: float
    session_id: str | None = None
    created_at: float = 0.0
    role: str | None = None


class ConfirmationStore:
    def __init__(
        self,
        ttl_seconds: int = 600,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._pending: dict[str, PendingConfirmation] = {}
        self._consumed: dict[str, float] = {}
        self._lock = threading.Lock()

    def create(
        self,
        user_id: str,
        action: Literal["report_lost", "report_found", "claim_item"],
        payload: dict[str, Any],
        *,
        session_id: str | None = None,
        role: str | None = None,
    ) -> tuple[str, PendingConfirmation]:
        confirmation_id = secrets.token_urlsafe(32)
        now = self._clock()
        pending = PendingConfirmation(
            user_id=user_id,
            action=action,
            payload=dict(payload),
            expires_at=now + self._ttl_seconds,
            session_id=session_id,
            created_at=now,
            role=role,
        )
        with self._lock:
            self._cleanup()
            self._pending[confirmation_id] = pending
        return confirmation_id, pending

    def consume(self, confirmation_id: str, user_id: str) -> PendingConfirmation:
        with self._lock:
            self._cleanup()
            if confirmation_id in self._consumed:
                raise ConfirmationError("确认信息已经使用")
            pending = self._pending.get(confirmation_id)
            if pending is None:
                raise ConfirmationError("确认信息无效或已过期")
            if pending.user_id != user_id:
                raise ConfirmationError("确认信息不属于当前用户")
            self._pending.pop(confirmation_id)
            self._consumed[confirmation_id] = pending.expires_at
            return pending

    def get(self, confirmation_id: str) -> PendingConfirmation | None:
        """只读探测：返回仍有效（未消费、未过期）的 pending，不做消耗。"""
        with self._lock:
            self._cleanup()
            pending = self._pending.get(confirmation_id)
            if pending is None:
                return None
            return PendingConfirmation(
                user_id=pending.user_id,
                action=pending.action,
                payload=dict(pending.payload),
                expires_at=pending.expires_at,
                session_id=pending.session_id,
                created_at=pending.created_at,
                role=pending.role,
            )

    def restore(self, confirmation_id: str, pending: PendingConfirmation) -> None:
        """把从会话恢复的 pending 重新 seed 回进程内 store（幂等、重启后可用）。"""
        with self._lock:
            self._cleanup()
            if confirmation_id in self._pending or confirmation_id in self._consumed:
                return
            if pending.expires_at <= self._clock():
                return
            self._pending[confirmation_id] = pending

    def _cleanup(self) -> None:
        now = self._clock()
        self._pending = {
            key: value for key, value in self._pending.items() if value.expires_at > now
        }
        self._consumed = {
            key: expires_at for key, expires_at in self._consumed.items() if expires_at > now
        }
