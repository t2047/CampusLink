"""Thread-safe, in-memory pending confirmation store."""

import secrets
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from threading import RLock
from types import MappingProxyType
from typing import Any, Dict, Mapping, Optional


ALLOWED_CONFIRMATION_TOOLS = frozenset(
    {"create_booking", "cancel_booking", "submit_maintenance_request"}
)


class ConfirmationError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return MappingProxyType(
            {key: _freeze(item) for key, item in deepcopy(value).items()}
        )
    if isinstance(value, list):
        return tuple(_freeze(item) for item in deepcopy(value))
    if isinstance(value, set):
        return frozenset(_freeze(item) for item in deepcopy(value))
    return deepcopy(value)


def _thaw(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _thaw(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_thaw(item) for item in value]
    if isinstance(value, frozenset):
        return [_thaw(item) for item in value]
    return deepcopy(value)


@dataclass
class PendingAction:
    confirmation_id: str
    user_id: str
    session_id: str
    tool_name: str
    exact_arguments: Mapping[str, Any]
    preview: Mapping[str, Any]
    created_at: datetime
    expires_at: datetime
    consumed: bool = False
    consumed_at: Optional[datetime] = None
    state: str = "PENDING"

    def arguments_copy(self) -> Dict[str, Any]:
        return _thaw(self.exact_arguments)

    def preview_copy(self) -> Dict[str, Any]:
        return _thaw(self.preview)


class ConfirmationStore:
    def __init__(self, ttl_seconds: int = 600, now_provider=utc_now) -> None:
        if ttl_seconds <= 0:
            raise ValueError("Confirmation TTL must be positive")
        self._ttl_seconds = ttl_seconds
        self._now_provider = now_provider
        self._actions: Dict[str, PendingAction] = {}
        self._lock = RLock()

    def _now(self) -> datetime:
        value = self._now_provider()
        return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)

    def create(
        self,
        user_id: str,
        session_id: str,
        tool_name: str,
        exact_arguments: Dict[str, Any],
        preview: Dict[str, Any],
    ) -> PendingAction:
        if tool_name not in ALLOWED_CONFIRMATION_TOOLS:
            raise ValueError("Tool is not eligible for confirmation")
        if not user_id or not session_id:
            raise ValueError("Both authenticated user and session are required")
        now = self._now()
        action = PendingAction(
            confirmation_id="facility-confirm-{0}".format(secrets.token_urlsafe(32)),
            user_id=str(user_id),
            session_id=str(session_id),
            tool_name=tool_name,
            exact_arguments=_freeze(exact_arguments),
            preview=_freeze(preview),
            created_at=now,
            expires_at=now + timedelta(seconds=self._ttl_seconds),
        )
        with self._lock:
            self._actions[action.confirmation_id] = action
        return self._snapshot(action)

    def _require_valid(
        self,
        confirmation_id: str,
        user_id: str,
        session_id: str,
    ) -> PendingAction:
        action = self._actions.get(confirmation_id)
        if action is None:
            raise ConfirmationError(
                "CONFIRMATION_NOT_FOUND", "Confirmation was not found"
            )
        if action.user_id != str(user_id):
            raise ConfirmationError(
                "CONFIRMATION_USER_MISMATCH", "Confirmation belongs to another user"
            )
        if action.session_id != str(session_id):
            raise ConfirmationError(
                "CONFIRMATION_SESSION_MISMATCH",
                "Confirmation belongs to another session",
            )
        if action.state == "CONSUMED" or action.consumed:
            raise ConfirmationError(
                "CONFIRMATION_CONSUMED", "Confirmation has already been used"
            )
        if action.state == "REJECTED":
            raise ConfirmationError(
                "CONFIRMATION_REJECTED", "Confirmation was rejected"
            )
        if action.state == "EXPIRED" or action.expires_at <= self._now():
            action.state = "EXPIRED"
            raise ConfirmationError("CONFIRMATION_EXPIRED", "Confirmation has expired")
        return action

    def get(self, confirmation_id: str, user_id: str, session_id: str) -> PendingAction:
        with self._lock:
            return self._snapshot(
                self._require_valid(confirmation_id, user_id, session_id)
            )

    def consume(
        self, confirmation_id: str, user_id: str, session_id: str
    ) -> PendingAction:
        with self._lock:
            action = self._require_valid(confirmation_id, user_id, session_id)
            action.consumed = True
            action.consumed_at = self._now()
            action.state = "CONSUMED"
            return self._snapshot(action)

    def reject(
        self, confirmation_id: str, user_id: str, session_id: str
    ) -> PendingAction:
        with self._lock:
            action = self._require_valid(confirmation_id, user_id, session_id)
            action.state = "REJECTED"
            return self._snapshot(action)

    def cleanup_expired(self) -> int:
        now = self._now()
        removed = 0
        with self._lock:
            expired_ids = [
                confirmation_id
                for confirmation_id, action in self._actions.items()
                if action.expires_at <= now and action.state == "PENDING"
            ]
            for confirmation_id in expired_ids:
                self._actions[confirmation_id].state = "EXPIRED"
                del self._actions[confirmation_id]
                removed += 1
        return removed

    @staticmethod
    def _snapshot(action: PendingAction) -> PendingAction:
        return PendingAction(
            confirmation_id=action.confirmation_id,
            user_id=action.user_id,
            session_id=action.session_id,
            tool_name=action.tool_name,
            exact_arguments=_freeze(action.arguments_copy()),
            preview=_freeze(action.preview_copy()),
            created_at=action.created_at,
            expires_at=action.expires_at,
            consumed=action.consumed,
            consumed_at=action.consumed_at,
            state=action.state,
        )
