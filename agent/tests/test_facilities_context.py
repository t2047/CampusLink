import unittest
from datetime import datetime, timedelta, timezone

from agent.facilities_agent.context import (
    ContextResolutionError,
    FacilitiesContextManager,
)


class MutableClock:
    def __init__(self, value):
        self.value = value

    def __call__(self):
        return self.value


class FacilitiesContextTest(unittest.TestCase):
    def setUp(self):
        self.clock = MutableClock(datetime(2026, 8, 9, 8, 0, tzinfo=timezone.utc))
        self.manager = FacilitiesContextManager(now_provider=self.clock)

    def test_search_candidates_are_limited_and_ranked(self):
        spaces = [
            {"spaceId": value, "name": "Space {0}".format(value)}
            for value in range(1, 9)
        ]
        self.manager.replace_search_results(spaces, "start", "end")
        candidates = self.manager.context.search_results.candidates
        self.assertEqual(5, len(candidates))
        self.assertEqual([1, 2, 3, 4, 5], [item.rank for item in candidates])

    def test_booking_candidates_are_limited_to_ten(self):
        bookings = [
            {"bookingId": value, "status": "CONFIRMED"} for value in range(1, 15)
        ]
        self.manager.replace_booking_candidates(bookings)
        self.assertEqual(10, len(self.manager.context.booking_candidates))

    def test_search_replacement_returns_complete_latest_snapshot(self):
        self.manager.replace_search_results(
            [{"spaceId": 1, "name": "Old"}], "old-start", "old-end"
        )
        self.manager.replace_search_results(
            [{"spaceId": 2, "name": "New"}], "new-start", "new-end"
        )
        snapshot = self.manager.snapshot()
        self.assertEqual(["facilities"], list(snapshot.keys()))
        facilities = snapshot["facilities"]
        self.assertEqual(1, facilities["version"])
        self.assertEqual(2, facilities["search_results"]["candidates"][0]["spaceId"])
        self.assertNotIn("Old", str(snapshot))

    def test_expired_search_cannot_resolve_first_one(self):
        self.manager.replace_search_results(
            [{"spaceId": 1, "name": "Room"}], "start", "end", ttl_seconds=10
        )
        self.clock.value += timedelta(seconds=11)
        with self.assertRaises(ContextResolutionError) as raised:
            self.manager.resolve_space_rank(1)
        self.assertEqual("SEARCH_CONTEXT_EXPIRED", raised.exception.code)

    def test_ambiguous_that_booking_is_not_guessed(self):
        self.manager.replace_booking_candidates(
            [
                {"bookingId": 10, "status": "CONFIRMED"},
                {"bookingId": 11, "status": "CONFIRMED"},
            ]
        )
        with self.assertRaises(ContextResolutionError) as raised:
            self.manager.resolve_booking_reference("that booking")
        self.assertEqual("AMBIGUOUS_BOOKING_REFERENCE", raised.exception.code)

    def test_that_booking_resolves_single_candidate(self):
        self.manager.replace_booking_candidates(
            [{"bookingId": 10, "status": "CONFIRMED"}]
        )
        self.assertEqual(
            10, self.manager.resolve_booking_reference("that booking").booking_id
        )

    def test_pending_booking_is_user_and_session_bound_and_expires(self):
        self.manager.set_pending_booking(
            user_id="42",
            session_id="session-1",
            space_id=4,
            booking_date="2026-08-17",
            start_date_time=None,
            end_date_time=None,
            missing_fields=["startDateTime", "endDateTime"],
            ttl_seconds=10,
        )
        self.assertIsNotNone(
            self.manager.get_pending_booking("42", "session-1")
        )
        self.assertIsNone(
            self.manager.get_pending_booking("43", "session-1")
        )
        self.assertIsNone(self.manager.context.pending_booking_draft)

        self.manager.set_pending_booking(
            user_id="42",
            session_id="session-1",
            space_id=4,
            booking_date="2026-08-17",
            start_date_time=None,
            end_date_time=None,
            missing_fields=["startDateTime", "endDateTime"],
            ttl_seconds=10,
        )
        self.clock.value += timedelta(seconds=11)
        self.assertIsNone(
            self.manager.get_pending_booking("42", "session-1")
        )
        self.assertIsNone(self.manager.context.pending_booking_draft)

    def test_untrusted_identity_and_token_fields_are_not_returned(self):
        manager = FacilitiesContextManager.from_shared_data(
            {
                "facilities": {
                    "version": 1,
                    "token": "must-not-survive",
                    "role": "ADMIN",
                    "email": "somebody@example.invalid",
                },
                "another_agent": {"private": "data"},
            },
            now_provider=self.clock,
        )
        snapshot = manager.snapshot()
        self.assertEqual(
            {"facilities": {"version": 1, "booking_candidates": []}}, snapshot
        )
        self.assertNotIn("token", str(snapshot))
        self.assertNotIn("ADMIN", str(snapshot))


if __name__ == "__main__":
    unittest.main()
