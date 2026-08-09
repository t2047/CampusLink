import unittest
from datetime import datetime

from agent.facilities_agent.datetime_parser import (
    CAMPUS_TIMEZONE,
    FacilitiesDateTimeParser,
)


class FacilitiesDateTimeParserTest(unittest.TestCase):
    def setUp(self):
        # Sunday, 9 August 2026, in Singapore.
        self.now = datetime(2026, 8, 9, 9, 30, tzinfo=CAMPUS_TIMEZONE)
        self.parser = FacilitiesDateTimeParser(lambda: self.now)

    def test_today_24_hour_range(self):
        parsed = self.parser.parse("today 14:00-16:00")
        self.assertEqual("2026-08-09T14:00:00", parsed.start_local_iso)
        self.assertEqual("2026-08-09T16:00:00", parsed.end_local_iso)

    def test_tomorrow_en_dash_range(self):
        parsed = self.parser.parse("tomorrow 2–4 pm")
        self.assertEqual("2026-08-10T14:00:00", parsed.start_local_iso)
        self.assertEqual("2026-08-10T16:00:00", parsed.end_local_iso)

    def test_next_monday_is_strictly_next(self):
        parsed = self.parser.parse("next Monday 2-4 pm")
        self.assertEqual("2026-08-10T14:00:00", parsed.start_local_iso)
        monday_now = datetime(2026, 8, 10, 9, 0, tzinfo=CAMPUS_TIMEZONE)
        parser = FacilitiesDateTimeParser(lambda: monday_now)
        self.assertEqual(
            "2026-08-17T14:00:00",
            parser.parse("next Monday 2-4 pm").start_local_iso,
        )

    def test_single_2_pm_parses_start_and_requests_end(self):
        parsed = self.parser.parse("tomorrow at 2 pm")
        self.assertEqual("2026-08-10T14:00:00", parsed.start_local_iso)
        self.assertIsNone(parsed.end_local_iso)
        self.assertTrue(parsed.needs_clarification)
        self.assertIn("end time", parsed.clarification)

    def test_at_2_does_not_guess_am_or_pm(self):
        parsed = self.parser.parse("tomorrow at 2")
        self.assertTrue(parsed.needs_clarification)
        self.assertIsNone(parsed.start)
        self.assertIn("am or pm", parsed.clarification)

    def test_range_without_meridiem_is_ambiguous(self):
        parsed = self.parser.parse("tomorrow 2-4")
        self.assertTrue(parsed.needs_clarification)
        self.assertIn("am or pm", parsed.clarification)


if __name__ == "__main__":
    unittest.main()
