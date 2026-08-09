"""用于 SSE 的短期内存事件存储。"""

import json
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AgentEvent:
    name: str
    payload: dict[str, Any]

    def to_sse(self) -> str:
        data = json.dumps(self.payload, ensure_ascii=False, separators=(",", ":"))
        return f"event: {self.name}\ndata: {data}\n\n"


class EventStore:
    def __init__(self, ttl_seconds: int, clock: Callable[[], float] = time.time) -> None:
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._events: dict[str, tuple[float, list[AgentEvent]]] = {}
        self._lock = threading.Lock()

    def append(self, request_id: str, event: AgentEvent) -> None:
        now = self._clock()
        with self._lock:
            self._cleanup(now)
            created, events = self._events.get(request_id, (now, []))
            events.append(event)
            self._events[request_id] = (created, events)

    def stream(self, request_id: str) -> Iterator[str]:
        now = self._clock()
        with self._lock:
            self._cleanup(now)
            events = list(self._events.get(request_id, (now, []))[1])
        if not events:
            events = [
                AgentEvent(
                    "agent_error",
                    {"code": "NOT_FOUND", "message": "没有找到该请求的事件"},
                )
            ]
        return (event.to_sse() for event in events)

    def _cleanup(self, now: float) -> None:
        self._events = {
            key: value for key, value in self._events.items() if now - value[0] < self._ttl_seconds
        }
