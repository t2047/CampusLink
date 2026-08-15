"""Deterministic Singapore campus date/time parsing helpers."""

import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from typing import ClassVar

CAMPUS_TIMEZONE_NAME = "Asia/Singapore"
# Singapore has used UTC+08:00 without daylight-saving changes since 1982.
# This named fixed-offset fallback keeps Python 3.8 support without a tzdata dependency.
CAMPUS_TIMEZONE = timezone(timedelta(hours=8), CAMPUS_TIMEZONE_NAME)


def singapore_now() -> datetime:
    return datetime.now(CAMPUS_TIMEZONE)


@dataclass(frozen=True)
class ParsedDateTimeRange:
    start: datetime | None
    end: datetime | None
    needs_clarification: bool = False
    clarification: str | None = None

    @property
    def start_local_iso(self) -> str | None:
        return (
            self.start.replace(tzinfo=None).isoformat(timespec="seconds")
            if self.start
            else None
        )

    @property
    def end_local_iso(self) -> str | None:
        return (
            self.end.replace(tzinfo=None).isoformat(timespec="seconds")
            if self.end
            else None
        )


class FacilitiesDateTimeParser:
    _WEEKDAYS: ClassVar[dict[str, int]] = {
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
    _ISO_DATE = re.compile(r"(?<!\d)(?P<date>\d{4}-\d{2}-\d{2})(?!\d)")
    _MONTH_DAY = re.compile(
        r"(?<![\d.])(?P<month>\d{1,2})[./](?P<day>\d{1,2})(?![\d.])"
    )
    _CHINESE_RANGE = re.compile(
        r"(?P<period>上午|早上|早晨|清晨|下午|傍晚|晚上|中午|夜里)?\s*(?P<start>\d{1,2})(?:点|時|时)"
        r"(?:(?P<start_minute>\d{1,2})分?)?\s*(?:到|至|[-–—])\s*"
        r"(?P<end_period>上午|早上|早晨|清晨|下午|傍晚|晚上|中午|夜里)?\s*(?P<end>\d{1,2})(?:点|時|时)"
        r"(?:(?P<end_minute>\d{1,2})分?)?"
    )
    _CHINESE_SINGLE = re.compile(
        r"(?P<period>上午|早上|早晨|清晨|下午|傍晚|晚上|中午|夜里)?\s*(?P<hour>\d{1,2})(?:点|時|时)"
        r"(?:(?P<minute>\d{1,2})\s*分(?:钟)?)?"
    )

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
        explicit = self._ISO_DATE.search(lowered)
        if explicit:
            try:
                return date.fromisoformat(explicit.group("date"))
            except ValueError:
                return current
        month_day = self._MONTH_DAY.search(lowered)
        if month_day:
            try:
                candidate = date(
                    current.year,
                    int(month_day.group("month")),
                    int(month_day.group("day")),
                )
                # A yearless campus booking date refers to its next occurrence.
                return (
                    candidate
                    if candidate >= current
                    else date(
                        current.year + 1,
                        candidate.month,
                        candidate.day,
                    )
                )
            except ValueError:
                return current
        if re.search(r"\btomorrow\b", lowered) or "明天" in text:
            return current + timedelta(days=1)
        if re.search(r"\btoday\b", lowered) or "今天" in text:
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
    def _normalize_meridiem(value: str | None) -> str | None:
        return value.lower().replace(".", "") if value else None

    @classmethod
    def _clock_time(
        cls,
        hour: int,
        minute: int,
        meridiem: str | None,
    ) -> tuple[time | None, str | None]:
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

    @staticmethod
    def _chinese_meridiem(period: str | None) -> str | None:
        if period in ("上午", "早上", "早晨", "清晨"):
            return "am"
        if period in ("下午", "傍晚", "晚上", "中午", "夜里"):
            return "pm"
        return None

    def _parse_chinese_time(
        self, text: str, target_date: date
    ) -> ParsedDateTimeRange | None:
        range_match = self._CHINESE_RANGE.search(text)
        if range_match:
            start_period = range_match.group("period")
            end_period = range_match.group("end_period") or start_period
            start_time, start_error = self._clock_time(
                int(range_match.group("start")),
                int(range_match.group("start_minute") or 0),
                self._chinese_meridiem(start_period or end_period),
            )
            end_time, end_error = self._clock_time(
                int(range_match.group("end")),
                int(range_match.group("end_minute") or 0),
                self._chinese_meridiem(end_period),
            )
            error = start_error or end_error
            if error:
                return ParsedDateTimeRange(None, None, True, error)
            return self._validate_range(
                datetime.combine(target_date, start_time, CAMPUS_TIMEZONE),
                datetime.combine(target_date, end_time, CAMPUS_TIMEZONE),
            )

        single_match = self._CHINESE_SINGLE.search(text)
        if single_match:
            parsed_time, error = self._clock_time(
                int(single_match.group("hour")),
                int(single_match.group("minute") or 0),
                self._chinese_meridiem(single_match.group("period")),
            )
            if error:
                return ParsedDateTimeRange(None, None, True, error)
            start = datetime.combine(target_date, parsed_time, CAMPUS_TIMEZONE)
            if start <= self._now():
                return ParsedDateTimeRange(
                    None,
                    None,
                    True,
                    "Please provide a future date and time.",
                )
            return self._with_duration_or_end(start, text)
        return None

    @staticmethod
    def _parse_duration(text: str) -> float | None:
        """Parse a duration phrase (中文“1小时/半小时” or “1 hour / half an hour”)."""
        match = re.search(
            r"(?P<hours>\d+(?:\.\d+)?)\s*(?:个)?(?:小)?时"
            r"|(?P<eng>\d+(?:\.\d+)?)\s*(?:hour|hr)s?",
            text,
            re.IGNORECASE,
        )
        if match:
            return float(match.group("hours") or match.group("eng"))
        if re.search(r"半小时|half\s*an?\s*hour", text, re.IGNORECASE):
            return 0.5
        return None

    def _with_duration_or_end(
        self, start: datetime, text: str
    ) -> ParsedDateTimeRange:
        duration = self._parse_duration(text)
        if duration is not None and duration > 0:
            return self._validate_range(
                start, start + timedelta(hours=duration)
            )
        return ParsedDateTimeRange(
            start,
            None,
            True,
            "Please provide an end time.",
        )

    @staticmethod
    def _cn_hour_to_arabic(text: str) -> str:
        """把中文数字钟点（九点/十点/十二点）转成阿拉伯数字（仅限“X点”模式）。"""
        cn_digits = {
            "十一": 11, "十二": 12, "十": 10,
            "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
            "六": 6, "七": 7, "八": 8, "九": 9,
        }
        def _replace(match: re.Match) -> str:
            word = match.group(1)
            return str(cn_digits.get(word, word))
        return re.sub(
            r"(?P<n>十一|十二|十|[一二三四五六七八九])(?=点|時|时)",
            _replace,
            text,
        )

    def _validate_range(
        self, start: datetime, end: datetime
    ) -> ParsedDateTimeRange:
        if end <= start:
            return ParsedDateTimeRange(
                None,
                None,
                True,
                "The end time must be after the start time.",
            )
        if start <= self._now():
            return ParsedDateTimeRange(
                None,
                None,
                True,
                "Please provide a future date and time.",
            )
        return ParsedDateTimeRange(start, end)

    def parse(self, text: str) -> ParsedDateTimeRange:
        target_date = self.parse_date(text)
        time_text = self._ISO_DATE.sub(" ", text)
        time_text = self._MONTH_DAY.sub(" ", time_text)
        time_text = self._cn_hour_to_arabic(time_text)
        chinese = self._parse_chinese_time(time_text, target_date)
        if chinese is not None:
            return chinese
        range_match = self._RANGE.search(time_text)
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
            return self._validate_range(start, end)

        single_match = self._SINGLE.search(time_text)
        if single_match:
            parsed_time, error = self._clock_time(
                int(single_match.group("hour")),
                int(single_match.group("minute") or 0),
                single_match.group("meridiem"),
            )
            if error:
                return ParsedDateTimeRange(None, None, True, error)
            start = datetime.combine(target_date, parsed_time, CAMPUS_TIMEZONE)
            if start <= self._now():
                return ParsedDateTimeRange(
                    None,
                    None,
                    True,
                    "Please provide a future date and time.",
                )
            return self._with_duration_or_end(start, time_text)

        if self._AMBIGUOUS_SINGLE.search(time_text):
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
