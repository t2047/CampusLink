import threading
import unittest
from datetime import datetime, timedelta, timezone

from agent.facilities_agent.confirmation import ConfirmationError, ConfirmationStore


class MutableClock:
    def __init__(self, value):
        self.value = value

    def __call__(self):
        return self.value


class ConfirmationStoreTest(unittest.TestCase):
    def setUp(self):
        self.clock = MutableClock(datetime(2026, 8, 9, tzinfo=timezone.utc))
        self.store = ConfirmationStore(ttl_seconds=600, now_provider=self.clock)

    def create(self):
        return self.store.create(
            "42",
            "session-a",
            "create_booking",
            {"spaceId": 4, "equipment": ["projector"]},
            {"spaceId": 4, "summary": "Book room"},
        )

    def test_high_entropy_id_and_default_shape(self):
        action = self.create()
        self.assertTrue(action.confirmation_id.startswith("facility-confirm-"))
        self.assertGreaterEqual(len(action.confirmation_id), 50)
        self.assertEqual("PENDING", action.state)
        self.assertEqual(timedelta(seconds=600), action.expires_at - action.created_at)

    def test_exact_arguments_are_deep_frozen(self):
        original = {"spaceId": 4, "nested": {"items": [1, 2]}}
        action = self.store.create(
            "42", "session-a", "create_booking", original, {"spaceId": 4}
        )
        original["spaceId"] = 99
        original["nested"]["items"].append(3)
        self.assertEqual(
            {"spaceId": 4, "nested": {"items": [1, 2]}},
            action.arguments_copy(),
        )
        with self.assertRaises(TypeError):
            action.exact_arguments["spaceId"] = 10

    def test_rejects_wrong_user(self):
        action = self.create()
        with self.assertRaises(ConfirmationError) as raised:
            self.store.consume(action.confirmation_id, "99", "session-a")
        self.assertEqual("CONFIRMATION_USER_MISMATCH", raised.exception.code)

    def test_missing_confirmation_is_rejected(self):
        with self.assertRaises(ConfirmationError) as raised:
            self.store.get("missing", "42", "session-a")
        self.assertEqual("CONFIRMATION_NOT_FOUND", raised.exception.code)

    def test_non_write_tool_cannot_be_stored(self):
        with self.assertRaises(ValueError):
            self.store.create("42", "session-a", "search_spaces", {}, {})

    def test_rejects_wrong_session(self):
        action = self.create()
        with self.assertRaises(ConfirmationError) as raised:
            self.store.consume(action.confirmation_id, "42", "session-b")
        self.assertEqual("CONFIRMATION_SESSION_MISMATCH", raised.exception.code)

    def test_rejects_expired_confirmation(self):
        action = self.create()
        self.clock.value += timedelta(seconds=601)
        with self.assertRaises(ConfirmationError) as raised:
            self.store.consume(action.confirmation_id, "42", "session-a")
        self.assertEqual("CONFIRMATION_EXPIRED", raised.exception.code)

    def test_consumed_confirmation_cannot_be_reused(self):
        action = self.create()
        consumed = self.store.consume(action.confirmation_id, "42", "session-a")
        self.assertTrue(consumed.consumed)
        self.assertEqual("CONSUMED", consumed.state)
        with self.assertRaises(ConfirmationError) as raised:
            self.store.consume(action.confirmation_id, "42", "session-a")
        self.assertEqual("CONFIRMATION_CONSUMED", raised.exception.code)

    def test_rejected_confirmation_cannot_be_consumed(self):
        action = self.create()
        rejected = self.store.reject(action.confirmation_id, "42", "session-a")
        self.assertEqual("REJECTED", rejected.state)
        with self.assertRaises(ConfirmationError) as raised:
            self.store.consume(action.confirmation_id, "42", "session-a")
        self.assertEqual("CONFIRMATION_REJECTED", raised.exception.code)

    def test_cleanup_expired(self):
        self.create()
        self.clock.value += timedelta(seconds=601)
        self.assertEqual(1, self.store.cleanup_expired())

    def test_consume_is_atomic(self):
        action = self.create()
        barrier = threading.Barrier(3)
        outcomes = []

        def consume():
            barrier.wait()
            try:
                self.store.consume(action.confirmation_id, "42", "session-a")
                outcomes.append("success")
            except ConfirmationError:
                outcomes.append("rejected")

        threads = [threading.Thread(target=consume) for _ in range(2)]
        for thread in threads:
            thread.start()
        barrier.wait()
        for thread in threads:
            thread.join()
        self.assertEqual(1, outcomes.count("success"))
        self.assertEqual(1, outcomes.count("rejected"))


if __name__ == "__main__":
    unittest.main()
