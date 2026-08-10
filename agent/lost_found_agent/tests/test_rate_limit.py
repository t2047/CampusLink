import pytest
from fastapi import HTTPException

from lost_found_agent.rate_limit import RateLimiter


def test_per_minute_limit_resets_after_window() -> None:
    current = [100.0]
    limiter = RateLimiter(2, 10, clock=lambda: current[0])

    limiter.check("user", "session")
    limiter.check("user", "session")
    with pytest.raises(HTTPException) as error:
        limiter.check("user", "session")
    assert error.value.status_code == 429

    current[0] += 60
    limiter.check("user", "new-session")


def test_session_limit_is_independent() -> None:
    limiter = RateLimiter(10, 1)

    limiter.check("user", "session")
    with pytest.raises(HTTPException) as error:
        limiter.check("user", "session")

    assert error.value.status_code == 429
