import pytest

from lost_found_agent.confirmation import ConfirmationError, ConfirmationStore


def test_confirmation_expires_and_cannot_be_replayed() -> None:
    now = [100.0]
    store = ConfirmationStore(ttl_seconds=10, clock=lambda: now[0])
    confirmation_id, _ = store.create("42", "claim_item", {"report_id": 7})

    with pytest.raises(ConfirmationError, match="无效或已过期"):
        store.consume(f"{confirmation_id}tampered", "42")

    assert store.consume(confirmation_id, "42").payload["report_id"] == 7
    with pytest.raises(ConfirmationError, match="已经使用"):
        store.consume(confirmation_id, "42")

    expired_id, _ = store.create("42", "claim_item", {"report_id": 8})
    now[0] += 11
    with pytest.raises(ConfirmationError, match="无效或已过期"):
        store.consume(expired_id, "42")
