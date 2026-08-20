"""用于 SSE 的短期内存事件存储。

本模块为失物招领 Agent 提供基于内存的、短期的「Agent 事件」存储，
供前端通过 SSE（Server-Sent Events，服务端推送）实时查看一次 Agent
请求的执行过程：

- AgentEvent：一条命名事件（如 agent_start / model_fallback /
  agent_error / agent_done），payload 为任意可 JSON 序列化字典，
  可序列化为标准 SSE 文本帧；
- EventStore：按 request_id 分组组织事件，每个请求一个事件列表；
  事件只在 TTL 秒内存活（由配置 agent_event_ttl_seconds 控制，如 300 秒），
  过期后整批惰性清理；
- 通过线程锁保证多线程读写安全，适配单实例部署。

典型流程：main.py 在 /agent/invoke 处理过程中不断 append() 记录事件，
前端随后通过 /agent/stream?request_id=... 以 SSE 形式 stream() 读取。
"""

import json  # 序列化事件 payload 为 JSON 字符串（SSE data 行）
import threading  # 线程锁，保护内存事件字典的并发读写
import time  # 默认时钟，用于计算事件存活时长
from collections.abc import Callable, Iterator  # 时钟类型与生成器返回类型注解
from dataclasses import dataclass  # 简化 AgentEvent 数据载体定义
from typing import Any  # 事件 payload 的通用字典类型


@dataclass(frozen=True)
class AgentEvent:
    """一条命名事件：名称 + 结构化载荷（不可变数据载体）。

    frozen=True 保证事件创建后不可修改；payload 须可 JSON 序列化。
    """

    name: str  # 事件名称，如 agent_start / agent_error / agent_done
    payload: dict[str, Any]  # 事件携带的结构化数据

    def to_sse(self) -> str:
        """把事件序列化为一条标准 SSE 文本帧。

        SSE 协议要求：每帧由 `event: <名称>` 与 `data: <数据>` 两行构成，
        以空行（\n\n）结尾。data 使用紧凑 JSON（去空白）以减小传输体积，
        并保留中文原文（ensure_ascii=False）。
        """
        # 紧凑序列化 payload：ensure_ascii=False 保留中文，separators 去掉多余空格
        data = json.dumps(self.payload, ensure_ascii=False, separators=(",", ":"))
        # 按 SSE 帧规范拼装：event 行 + data 行 + 空行（\n\n 表示帧结束）
        return f"event: {self.name}\ndata: {data}\n\n"


class EventStore:
    """按请求分组、短期存活的 Agent 事件内存存储。

    关键属性：
    - _events: request_id -> (创建时间戳, 该请求的事件列表)；
    - _ttl_seconds: 事件存活时长，超过后整批清理；
    - _clock: 可注入时钟（测试用），默认 time.time；
    - _lock: 线程锁，保护 _events 的并发读写。

    生命周期：append() 追加事件并记录请求的创建时间 -> stream() 按序读取；
    每次 append/stream 都会触发 _cleanup() 惰性删除超龄请求的事件。
    """

    def __init__(self, ttl_seconds: int, clock: Callable[[], float] = time.time) -> None:
        self._ttl_seconds = ttl_seconds  # 事件存活时长（秒），过期即清理
        self._clock = clock  # 可注入时钟，默认 time.time
        self._events: dict[str, tuple[float, list[AgentEvent]]] = {}  # 请求分组事件存储
        self._lock = threading.Lock()  # 全局互斥锁

    def append(self, request_id: str, event: AgentEvent) -> None:
        """向指定请求追加一条事件；请求尚无事件时首次创建其事件列表。

        入参：
        - request_id: 请求追踪 id，用于分组事件；
        - event: 要追加的 AgentEvent。

        调用场景：main.py 在 /agent/invoke 处理过程中按阶段记录事件。
        无返回值。
        """
        now = self._clock()  # 取当前时间，用作「首次创建时间」与清理基准
        with self._lock:  # 加锁保证「读取-修改-写回」的原子性
            self._cleanup(now)  # 顺带清理超龄请求，控制内存增长
            # 取出该请求已有的事件列表；首次出现时以 (now, []) 为默认值
            created, events = self._events.get(request_id, (now, []))
            events.append(event)  # 追加新事件
            self._events[request_id] = (created, events)  # 写回（created 保持首次时间）

    def stream(self, request_id: str) -> Iterator[str]:
        """读取指定请求的全部事件，返回逐帧 SSE 文本生成器。

        入参：request_id 请求追踪 id。
        返回值：逐帧 SSE 字符串的可迭代对象；若该请求没有事件，则返回一条
        agent_error（NOT_FOUND）事件帧。

        调用场景：/agent/stream 端点把本方法返回的生成器交给
        StreamingResponse，以 text/event-stream 媒体类型推送给前端。
        """
        now = self._clock()
        with self._lock:
            self._cleanup(now)
            # 拷贝该请求的事件列表（默认空），避免在锁外操作共享结构
            events = list(self._events.get(request_id, (now, []))[1])
        # 没有事件：构造一条 NOT_FOUND 错误事件，让前端能区分「无记录」与「空推送」
        if not events:
            events = [
                AgentEvent(
                    "agent_error",
                    {"code": "NOT_FOUND", "message": "没有找到该请求的事件"},
                )
            ]
        # 惰性生成器：逐个把 AgentEvent 转成 SSE 帧，按需消费、不占额外内存
        return (event.to_sse() for event in events)

    def _cleanup(self, now: float) -> None:
        """惰性清理超过 TTL 的请求事件（在 append/stream 的锁内调用）。

        以 now 为基准：只要「当前时间 - 请求创建时间」不小于 TTL 秒，
        就丢弃该请求的整批事件以释放内存。
        """
        # 过滤重建：仅保留存活时间仍小于 TTL 的请求
        self._events = {
            key: value for key, value in self._events.items() if now - value[0] < self._ttl_seconds
        }
