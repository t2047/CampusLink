"""单实例开发阶段的内存限流实现。

本模块为失物招领 Agent 提供内存版限流器（RateLimiter），用于保护内部
Campus API 与 LLM 资源不被单个用户 / 会话过度消耗：

- 每分钟限流：按「用户 id」维护过去 60 秒内请求时间戳的队列（滑动窗口），
  队列长度达到 per_minute 上限即拒绝；
- 会话限流：按「会话 id」累计该会话的总请求数，达到 per_session 上限即拒绝。

由于状态全部保存在进程内存中，本实现只适用于单实例部署；多实例时请求会
分散到不同进程、统计互不可见（模块名与 docstring 明确标注为开发阶段方案）。
超限时抛出 429 Too Many Requests 的 HTTPException，带中文提示文案。
"""

import threading  # 线程锁，保证并发请求下限流统计的原子性
import time  # 默认时钟，用于滑动窗口时间判断
from collections import defaultdict, deque  # defaultdict 免初始化计数；deque 作时间戳队列
from collections.abc import Callable  # 可注入时钟的类型注解

from fastapi import HTTPException, status  # FastAPI 标准 429 异常与状态码


class RateLimiter:
    """内存版滑动窗口限流器：每分钟限流 + 每会话限流。

    关键属性：
    - _user_events: user_id -> 最近请求时间戳的 deque（升序，队首最旧）；
    - _session_counts: session_id -> 该会话累计请求次数；
    - _per_minute / _per_session: 两种限流的阈值；
    - _lock: 线程锁，保护上述统计数据的并发读写。
    """

    def __init__(
        self,
        per_minute: int,
        per_session: int,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._per_minute = per_minute  # 每个用户每分钟最多请求次数
        self._per_session = per_session  # 每个会话累计最多请求次数
        self._clock = clock  # 可注入时钟（测试用），默认 time.time
        # 每用户的时间戳队列（滑动窗口），deque 便于从队首淘汰最旧记录
        self._user_events: dict[str, deque[float]] = defaultdict(deque)
        self._session_counts: dict[str, int] = defaultdict(int)  # 每会话累计请求计数
        self._lock = threading.Lock()  # 全局互斥锁

    def check(self, user_id: str, session_id: str) -> None:
        """在每次 Agent 请求处理前检查限流；超限则抛出 429。

        入参：
        - user_id: 当前用户 id（每分钟限流的维度）；
        - session_id: 当前会话 id（会话总次数限流的维度）。

        异常：HTTPException(429)，detail 为中文提示文案。

        调用场景：main.py 的 /agent/invoke 在安全验证后、真正处理前调用。
        限流通过则无返回值；未通过直接抛异常，由 FastAPI 转为 429 响应。
        """
        now = self._clock()  # 取当前时间，作为滑动窗口判断基准
        with self._lock:  # 加锁保证「统计 + 判断 + 更新」整体原子，避免并发穿透
            events = self._user_events[user_id]
            # 滑动窗口淘汰：把距今已超过 60 秒的旧时间戳从队首弹出，
            # 使队列始终只代表「最近 1 分钟」内的请求（滑动窗口语义）
            while events and now - events[0] >= 60:
                events.popleft()
            # 每分钟限流：窗口内请求数已达上限 -> 拒绝
            if len(events) >= self._per_minute:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="请求过于频繁，请稍后再试",
                )
            # 会话限流：该会话累计请求数已达上限 -> 拒绝
            if self._session_counts[session_id] >= self._per_session:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="本次会话请求次数已达到上限",
                )
            # 通过检查：记录本次请求（窗口时间戳 + 会话计数），供后续请求判断
            events.append(now)
            self._session_counts[session_id] += 1
