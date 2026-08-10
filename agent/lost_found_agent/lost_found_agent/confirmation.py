"""写操作确认状态：与用户绑定、短期有效、一次性使用。"""

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
    action: Literal["report_lost", "claim_item"]
    payload: dict[str, Any]
    expires_at: float


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
        action: Literal["report_lost", "claim_item"],
        payload: dict[str, Any],
    ) -> tuple[str, PendingConfirmation]:
        confirmation_id = secrets.token_urlsafe(32)
        pending = PendingConfirmation(
            user_id=user_id,
            action=action,
            payload=dict(payload),
            expires_at=self._clock() + self._ttl_seconds,
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

    def _cleanup(self) -> None:
        now = self._clock()
        self._pending = {
            key: value for key, value in self._pending.items() if value.expires_at > now
        }
        self._consumed = {
            key: expires_at for key, expires_at in self._consumed.items() if expires_at > now
        }
