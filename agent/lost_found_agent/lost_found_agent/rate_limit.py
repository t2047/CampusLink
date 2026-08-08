"""单实例开发阶段的内存限流实现。"""

import threading
import time
from collections import defaultdict, deque
from collections.abc import Callable

from fastapi import HTTPException, status


class RateLimiter:
    def __init__(
        self,
        per_minute: int,
        per_session: int,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._per_minute = per_minute
        self._per_session = per_session
        self._clock = clock
        self._user_events: dict[str, deque[float]] = defaultdict(deque)
        self._session_counts: dict[str, int] = defaultdict(int)
        self._lock = threading.Lock()

    def check(self, user_id: str, session_id: str) -> None:
        now = self._clock()
        with self._lock:
            events = self._user_events[user_id]
            while events and now - events[0] >= 60:
                events.popleft()
            if len(events) >= self._per_minute:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="请求过于频繁，请稍后再试",
                )
            if self._session_counts[session_id] >= self._per_session:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="本次会话请求次数已达到上限",
                )
            events.append(now)
            self._session_counts[session_id] += 1
