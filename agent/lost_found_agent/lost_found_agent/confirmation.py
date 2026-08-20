"""写操作确认状态：与用户绑定、短期有效、一次性使用。

本模块是失物招领 Agent 的「写操作二次确认」内存存储，负责在真正调用
Campus 内部 API 执行写操作（报失 / 报捡 / 认领）之前，先向用户展示待确认
的请求并暂存其状态：

- create() 生成高熵随机确认 ID（token），并记录该确认属于哪个用户、要执行
  哪个写操作以及完整的载荷快照；
- 确认有默认 600 秒（10 分钟）的有效期，过期后会被惰性清理；
- 每个确认 ID 只能消费一次（一次性），消费后立即移入「已消费」区，
  防止同一确认被重复提交；
- 所有读写都通过线程锁保护，配合单实例部署使用。

在 rules.py 中：写操作先经 ConfirmationStore.create() 暂存并返回确认提示，
用户显式同意后携带 confirmation_id 调用 consume() 解锁真正执行。
"""

import secrets  # 生成高熵随机确认 token（URL 安全）
import threading  # 线程锁，保证并发读写内存存储时的原子性
import time  # 默认时钟，用于计算确认过期时间
from collections.abc import Callable  # 可注入时钟的类型注解
from dataclasses import dataclass  # 定义不可变数据载体 PendingConfirmation
from typing import Any, Literal  # 载荷字典类型与动作字面量联合


class ConfirmationError(ValueError):
    """确认相关的业务错误：已使用 / 无效 / 已过期 / 不属于当前用户。

    继承 ValueError，调用方可用 try/except ValueError 统一捕获；
    携带的中文 message 直接作为面向用户的提示文案。
    """
    pass


@dataclass(frozen=True)
class PendingConfirmation:
    """一次待确认写操作的状态快照（不可变数据载体）。

    frozen=True 保证创建后不可修改，防止确认状态在消费前被意外篡改。
    """

    user_id: str  # 发起该写操作的用户 id，消费时必须与当前请求用户一致
    action: Literal["report_lost", "report_found", "claim_item"]  # 要执行的写操作类型
    payload: dict[str, Any]  # 写操作载荷（如报失表单字段），创建时浅拷贝以防外部改动
    expires_at: float  # 过期时间戳（秒），超过该时间后确认视为无效


class ConfirmationStore:
    """写操作确认的内存存储：与用户绑定、短期有效、一次性使用。

    关键属性：
    - _pending: 尚未消费的待确认记录（确认 ID -> 状态快照）；
    - _consumed: 已被消费过的确认记录（确认 ID -> 原过期时间戳），
      用于检测「重复使用」；
    - _ttl_seconds: 确认有效期（秒），默认 600；
    - _lock: 线程锁，保护 _pending / _consumed 的并发读写。

    生命周期：create() 创建待确认记录 -> 用户确认后 consume() 取出并消费；
    过期未消费的记录由 _cleanup() 在下一次任何操作时惰性清理。
    """

    def __init__(
        self,
        ttl_seconds: int = 600,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._ttl_seconds = ttl_seconds  # 确认有效期（秒），超时后不可消费
        self._clock = clock  # 可注入时钟（测试可替换），默认 time.time
        self._pending: dict[str, PendingConfirmation] = {}  # 待消费确认：确认 ID -> 状态
        self._consumed: dict[str, float] = {}  # 已消费确认：确认 ID -> 原过期时间戳
        self._lock = threading.Lock()  # 全局互斥锁，串行化所有读写

    def create(
        self,
        user_id: str,
        action: Literal["report_lost", "report_found", "claim_item"],
        payload: dict[str, Any],
    ) -> tuple[str, PendingConfirmation]:
        """创建一次待确认写操作，返回 (确认 ID, 状态快照)。

        入参：
        - user_id: 发起写操作的用户 id，消费时必须匹配；
        - action: 写操作类型（报失 / 报捡 / 认领）；
        - payload: 写操作载荷，会浅拷贝后存入，防止外部后续修改影响快照。

        返回：
        - confirmation_id: 用于告知用户的高熵确认 token；
        - pending: 存好的状态快照。

        调用场景：rules.py 在需要用户确认的写操作前调用，把确认 ID 拼进
        回复，提示用户「如需确认请回复该确认码」。
        """
        # token_urlsafe(32) 生成约 43 字符的 URL 安全随机串，不可预测、难以伪造
        confirmation_id = secrets.token_urlsafe(32)
        # 组装状态快照：绑定用户与动作，过期时间 = 当前时间 + 有效期
        pending = PendingConfirmation(
            user_id=user_id,
            action=action,
            payload=dict(payload),  # 浅拷贝，避免外部对同一对象后续修改影响快照
            expires_at=self._clock() + self._ttl_seconds,
        )
        with self._lock:  # 加锁保证并发安全
            self._cleanup()  # 先清理过期记录，防止堆积
            self._pending[confirmation_id] = pending  # 写入待确认区
        return confirmation_id, pending

    def consume(self, confirmation_id: str, user_id: str) -> PendingConfirmation:
        """消费（使用）一次确认：校验通过后取出并标记为已使用。

        入参：
        - confirmation_id: 用户提供的确认 token；
        - user_id: 当前请求的用户 id，须与创建时一致。

        返回：对应的 PendingConfirmation 状态快照，调用方据此真正执行写操作。

        异常（ConfirmationError，均带面向用户的中文提示）：
        - 该确认已被使用过；
        - 确认不存在或已过期；
        - 确认属于其他用户。

        调用场景：用户对确认提示作出同意后，携带 confirmation_id 再次请求，
        rules.py 调用本方法解锁真正执行写操作。
        """
        with self._lock:  # 加锁保证「检查 + 移除」整体原子，防止并发重复消费
            self._cleanup()
            # 一、查重：已消费过的确认 ID 直接拒绝（一次性语义）
            if confirmation_id in self._consumed:
                raise ConfirmationError("确认信息已经使用")
            # 二、查有效性：待确认区查不到说明不存在或已被清理
            pending = self._pending.get(confirmation_id)
            if pending is None:
                raise ConfirmationError("确认信息无效或已过期")
            # 三、校验归属：确认只能由创建它的用户消费
            if pending.user_id != user_id:
                raise ConfirmationError("确认信息不属于当前用户")
            # 校验通过：从待确认区移除，并记入已消费区（记下原过期时间供清理用）
            self._pending.pop(confirmation_id)
            self._consumed[confirmation_id] = pending.expires_at
            return pending

    def _cleanup(self) -> None:
        """惰性清理所有已过期的确认记录（仅在加锁的 create/consume 内调用）。

        用「按过期时间过滤」重建字典：_pending 只保留尚未过期者；
        _consumed 只保留仍在其有效期内者——过期后确认再也无法消费，
        记录便失去意义，可安全丢弃以释放内存。本方法仅做清理，无返回值。
        """
        now = self._clock()  # 取当前时间作为过期判断基准
        # 过滤待确认区：保留 expires_at 仍大于 now（尚未过期）的记录
        self._pending = {
            key: value for key, value in self._pending.items() if value.expires_at > now
        }
        # 过滤已消费区：只保留原过期时间仍大于 now 的记录
        self._consumed = {
            key: expires_at for key, expires_at in self._consumed.items() if expires_at > now
        }
