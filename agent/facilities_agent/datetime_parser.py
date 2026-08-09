"""Deterministic Singapore campus date/time parsing helpers."""

import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from typing import Callable, Optional, Tuple


CAMPUS_TIMEZONE_NAME = "Asia/Singapore"
# Singapore has used UTC+08:00 without daylight-saving changes since 1982.
# This named fixed-offset fallback keeps Python 3.8 support without a tzdata dependency.
CAMPUS_TIMEZONE = timezone(timedelta(hours=8), CAMPUS_TIMEZONE_NAME)


def singapore_now() -> datetime:
    return datetime.now(CAMPUS_TIMEZONE)


@dataclass(frozen=True)
class ParsedDateTimeRange:
    start: Optional[datetime]
    end: Optional[datetime]
    needs_clarification: bool = False
    clarification: Optional[str] = None

    @property
    def start_local_iso(self) -> Optional[str]:
        return (
            self.start.replace(tzinfo=None).isoformat(timespec="seconds")
            if self.start
            else None
        )

    @property
    def end_local_iso(self) -> Optional[str]:
        return (
            self.end.replace(tzinfo=None).isoformat(timespec="seconds")
            if self.end
            else None
        )


class FacilitiesDateTimeParser:
    _WEEKDAYS = {
        "monday": 0,
        "tuesday": 1,
        "wednesday": 2,
        "thursday": 3,
        "friday": 4,
        "saturday": 5,
        "sunday": 6,
    }

    _RANGE = re.compile(
        r"(?<!\d)(?P<start_hour>\d{1,2})(?::(?P<start_minute>\d{2}))?\s*"
        r"(?P<start_meridiem>a\.?m\.?|p\.?m\.?)?\s*"
        r"(?:-|\u2013|\u2014|\bto\b)\s*"
        r"(?P<end_hour>\d{1,2})(?::(?P<end_minute>\d{2}))?\s*"
        r"(?P<end_meridiem>a\.?m\.?|p\.?m\.?)?",
        re.IGNORECASE,
    )
    _SINGLE = re.compile(
        r"(?:\bat\s+)?(?<![-\d])(?P<hour>\d{1,2})(?::(?P<minute>\d{2}))?\s*"
        r"(?P<meridiem>a\.?m\.?|p\.?m\.?)(?!\w)",
        re.IGNORECASE,
    )
    _AMBIGUOUS_SINGLE = re.compile(r"\bat\s+(?P<hour>\d{1,2})(?![:\d])", re.IGNORECASE)

    def __init__(self, now_provider: Callable[[], datetime] = singapore_now) -> None:
        self._now_provider = now_provider

    def _now(self) -> datetime:
        current = self._now_provider()
        if current.tzinfo is None:
            return current.replace(tzinfo=CAMPUS_TIMEZONE)
        return current.astimezone(CAMPUS_TIMEZONE)

    def parse_date(self, text: str) -> date:
        lowered = text.lower()
        current = self._now().date()
        if re.search(r"\btomorrow\b", lowered):
            return current + timedelta(days=1)
        if re.search(r"\btoday\b", lowered):
            return current
        weekday_match = re.search(
            r"\bnext\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b",
            lowered,
        )
        if weekday_match:
            target = self._WEEKDAYS[weekday_match.group(1)]
            days = (target - current.weekday()) % 7
            return current + timedelta(days=days if days else 7)
        return current

    @staticmethod
    def _normalize_meridiem(value: Optional[str]) -> Optional[str]:
        return value.lower().replace(".", "") if value else None

    @classmethod
    def _clock_time(
        cls,
        hour: int,
        minute: int,
        meridiem: Optional[str],
    ) -> Tuple[Optional[time], Optional[str]]:
        normalized = cls._normalize_meridiem(meridiem)
        if minute > 59 or hour > 23:
            return None, "Please provide a valid time."
        if normalized:
            if hour < 1 or hour > 12:
                return None, "12-hour times must be between 1 and 12."
            hour = hour % 12 + (12 if normalized == "pm" else 0)
        elif hour <= 12:
            return None, "Please clarify whether the time is am or pm."
        return time(hour=hour, minute=minute), None

    def parse(self, text: str) -> ParsedDateTimeRange:
        target_date = self.parse_date(text)
        range_match = self._RANGE.search(text)
        if range_match:
            start_meridiem = range_match.group("start_meridiem")
            end_meridiem = range_match.group("end_meridiem")
            if not start_meridiem and end_meridiem:
                start_meridiem = end_meridiem
            if start_meridiem and not end_meridiem:
                end_meridiem = start_meridiem

            start_time, start_error = self._clock_time(
                int(range_match.group("start_hour")),
                int(range_match.group("start_minute") or 0),
                start_meridiem,
            )
            end_time, end_error = self._clock_time(
                int(range_match.group("end_hour")),
                int(range_match.group("end_minute") or 0),
                end_meridiem,
            )
            error = start_error or end_error
            if error:
                return ParsedDateTimeRange(None, None, True, error)
            start = datetime.combine(target_date, start_time, CAMPUS_TIMEZONE)
            end = datetime.combine(target_date, end_time, CAMPUS_TIMEZONE)
            return ParsedDateTimeRange(start, end)

        single_match = self._SINGLE.search(text)
        if single_match:
            parsed_time, error = self._clock_time(
                int(single_match.group("hour")),
                int(single_match.group("minute") or 0),
                single_match.group("meridiem"),
            )
            if error:
                return ParsedDateTimeRange(None, None, True, error)
            start = datetime.combine(target_date, parsed_time, CAMPUS_TIMEZONE)
            return ParsedDateTimeRange(
                start,
                None,
                True,
                "Please provide an end time.",
            )

        if self._AMBIGUOUS_SINGLE.search(text):
            return ParsedDateTimeRange(
                None,
                None,
                True,
                "Please clarify whether the time is am or pm and provide an end time.",
            )
        return ParsedDateTimeRange(
            None,
            None,
            True,
            "Please provide a date and a start/end time.",
        )
