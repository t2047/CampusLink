"""写操作确认（confirmation）测试。

覆盖 `lost_found_agent.confirmation.ConfirmationStore` 的一次性 / 有效期语义：
- 篡改过的确认 id 不可用（报"无效或已过期"）；
- 确认 id 只能消费一次（重复消费报"已经使用"）；
- 超过 TTL 后确认失效（报"无效或已过期"）。

策略：注入可控 clock 前移时间，配合 pytest.raises 断言中文错误文案。
"""

import pytest

from lost_found_agent.confirmation import ConfirmationError, ConfirmationStore


def test_confirmation_expires_and_cannot_be_replayed() -> None:
    """确认信息必须一次性使用、与用户绑定并在 TTL 后过期。"""
    now = [100.0]  # 可控时钟：list 便于在测试里前移时间
    store = ConfirmationStore(ttl_seconds=10, clock=lambda: now[0])  # 有效期 10 秒
    confirmation_id, _ = store.create("42", "claim_item", {"report_id": 7})  # 为用户 42 生成认领确认

    # 篡改 id：store 中不存在的键消费，必须报"无效或已过期"
    with pytest.raises(ConfirmationError, match="无效或已过期"):
        store.consume(f"{confirmation_id}tampered", "42")

    assert store.consume(confirmation_id, "42").payload["report_id"] == 7  # 原始 id 首次消费成功
    with pytest.raises(ConfirmationError, match="已经使用"):
        store.consume(confirmation_id, "42")  # 二次消费同一 id 被拒（防重放）

    expired_id, _ = store.create("42", "claim_item", {"report_id": 8})  # 再造一个确认
    now[0] += 11  # 时间前移超过 TTL（10 秒）
    with pytest.raises(ConfirmationError, match="无效或已过期"):
        store.consume(expired_id, "42")  # 过期确认不可消费
