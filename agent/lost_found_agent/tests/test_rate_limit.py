"""内存限流（rate_limit）测试。

覆盖 `lost_found_agent.rate_limit.RateLimiter`：
- 每用户每分钟上限：超限抛 429，窗口滑动后恢复；
- 每会话上限独立计数：会话维度超限同样抛 429。

策略：注入可控 clock，模拟时间前进以触发窗口过期。
"""

import pytest
from fastapi import HTTPException

from lost_found_agent.rate_limit import RateLimiter


def test_per_minute_limit_resets_after_window() -> None:
    """每用户每分钟限流：达到上限抛 429，60 秒窗口滑动后再次放行。"""
    current = [100.0]  # 用可变 list 保存"当前时间"，供 clock 读取并可在测试中前移
    limiter = RateLimiter(2, 10, clock=lambda: current[0])  # 每分钟 2 次 / 每会话 10 次

    limiter.check("user", "session")  # 第 1 次放行
    limiter.check("user", "session")  # 第 2 次放行
    with pytest.raises(HTTPException) as error:
        limiter.check("user", "session")  # 第 3 次超限
    assert error.value.status_code == 429  # 返回 429 Too Many Requests

    current[0] += 60  # 时间前移 60 秒，让上一分钟的事件过期
    limiter.check("user", "new-session")  # 新会话 + 旧事件过期，重新放行


def test_session_limit_is_independent() -> None:
    """每会话上限独立于用户维度：会话维度超限同样抛 429。"""
    limiter = RateLimiter(10, 1)  # 每用户每分钟 10 次，但每会话仅 1 次

    limiter.check("user", "session")  # 该会话第 1 次放行
    with pytest.raises(HTTPException) as error:
        limiter.check("user", "session")  # 同一会话第 2 次超限（用户维度还没到 10 次）

    assert error.value.status_code == 429
