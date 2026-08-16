"""Calendar service for the CampusLink mail module.

Provides per-user calendar event CRUD backed by a small SQLite database
(``calendar.db`` next to the service, path overridable via ``MAIL_CALENDAR_DB``),
plus schedule extraction from recent emails.

Extraction strategy (``MAIL_CALENDAR_EXTRACT_MODE``, default ``auto``):
  * ``auto`` - use the LLM (DeepSeek via ``MAIL_LLM_*``/``DEEPSEEK_*``) when a
    key is configured, falling back to the rule parser on any failure;
  * ``llm``  - LLM only (an LLM failure surfaces as an error);
  * ``rules`` - always use the built-in rule parser.

Events are scoped to the caller's identity: the verified user id returned by
``main._user_from_auth`` (email for web/mobile user JWTs, or the ``sub`` of an
internal MCP-gateway token for the chat path) is used as the ``user_id``,
mirroring how the rest of the mail service treats users.

Endpoints are wired in ``main.py`` under ``/api/mail/calendar/**``.
"""

from __future__ import annotations

import json
import logging
import os
import re
import sqlite3
import threading
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

from pydantic import BaseModel, Field

from . import config
from .models import MailMessage

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Storage
# ---------------------------------------------------------------------------

# <repo>/agent/mail_agent/calendar.db by default (the service owns its data dir).
CALENDAR_DB_PATH = Path(
    os.environ.get("MAIL_CALENDAR_DB", str(config.SERVICE_DIR / "calendar.db"))
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS calendar_events (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    location        TEXT NOT NULL DEFAULT '',
    start_time      TEXT NOT NULL,
    end_time        TEXT NOT NULL,
    all_day         INTEGER NOT NULL DEFAULT 0,
    source          TEXT NOT NULL DEFAULT 'manual',
    source_email_id TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_start
    ON calendar_events (user_id, start_time);
"""

_db_lock = threading.Lock()


def _connect() -> sqlite3.Connection:
    CALENDAR_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(CALENDAR_DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    with _db_lock:
        conn.executescript(_SCHEMA)
    return conn


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------


class CalendarEvent(BaseModel):
    id: str
    user_id: str
    title: str
    description: str = ""
    location: str = ""
    start_time: str
    end_time: str
    all_day: bool = False
    source: str = "manual"
    source_email_id: str | None = None
    created_at: str
    updated_at: str


class CalendarEventRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=5000)
    location: str = Field(default="", max_length=300)
    start_time: str = Field(min_length=10, max_length=30)
    end_time: str = Field(min_length=10, max_length=30)
    all_day: bool = False

    def validate_times(self) -> None:
        try:
            start = datetime.fromisoformat(self.start_time)
            end = datetime.fromisoformat(self.end_time)
        except ValueError as exc:
            raise ValueError("start_time/end_time must be ISO datetimes") from exc
        if end < start:
            raise ValueError("end_time must be after start_time")


class CalendarEventUpdate(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=5000)
    location: str | None = Field(default=None, max_length=300)
    start_time: str | None = Field(default=None, min_length=10, max_length=30)
    end_time: str | None = Field(default=None, min_length=10, max_length=30)
    all_day: bool | None = None


class ExtractedSchedule(BaseModel):
    """A schedule proposed from an email; shown to the user before import."""

    key: str
    title: str
    description: str = ""
    location: str = ""
    start_time: str
    end_time: str
    all_day: bool = False
    source_email_id: str | None = None
    email_subject: str = ""


class ImportRequest(BaseModel):
    events: list[ExtractedSchedule] = Field(default_factory=list, max_length=200)


class ImportResponse(BaseModel):
    imported: int
    skipped: int
    events: list[CalendarEvent]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _row_to_event(row: sqlite3.Row) -> CalendarEvent:
    return CalendarEvent(
        id=row["id"],
        user_id=row["user_id"],
        title=row["title"],
        description=row["description"] or "",
        location=row["location"] or "",
        start_time=row["start_time"],
        end_time=row["end_time"],
        all_day=bool(row["all_day"]),
        source=row["source"],
        source_email_id=row["source_email_id"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


# ---------------------------------------------------------------------------
# CRUD
# ---------------------------------------------------------------------------


def create_event(user_id: str, request: CalendarEventRequest) -> CalendarEvent:
    request.validate_times()
    now = _now_iso()
    event = CalendarEvent(
        id=uuid.uuid4().hex,
        user_id=user_id,
        title=request.title.strip(),
        description=request.description.strip(),
        location=request.location.strip(),
        start_time=request.start_time,
        end_time=request.end_time,
        all_day=request.all_day,
        source="manual",
        created_at=now,
        updated_at=now,
    )
    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO calendar_events (
                id, user_id, title, description, location, start_time, end_time,
                all_day, source, source_email_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                event.id, user_id, event.title, event.description, event.location,
                event.start_time, event.end_time, int(event.all_day),
                event.source, None, event.created_at, event.updated_at,
            ),
        )
    return event


def list_events(
    user_id: str, start: str | None = None, end: str | None = None
) -> list[CalendarEvent]:
    """List events for a user, optionally bounded by ``[start, end)`` ISO datetimes."""
    clauses = ["user_id = ?"]
    params: list[object] = [user_id]
    if start:
        clauses.append("end_time > ?")
        params.append(start)
    if end:
        clauses.append("start_time < ?")
        params.append(end)
    with _connect() as conn:
        rows = conn.execute(
            f"SELECT * FROM calendar_events WHERE {' AND '.join(clauses)} "
            "ORDER BY start_time ASC",
            params,
        ).fetchall()
    return [_row_to_event(row) for row in rows]


def get_event(user_id: str, event_id: str) -> CalendarEvent | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT * FROM calendar_events WHERE id = ? AND user_id = ?",
            (event_id, user_id),
        ).fetchone()
    return _row_to_event(row) if row else None


def update_event(
    user_id: str, event_id: str, request: CalendarEventUpdate
) -> CalendarEvent | None:
    current = get_event(user_id, event_id)
    if current is None:
        return None
    updates: dict[str, object] = {}
    if request.title is not None:
        updates["title"] = request.title.strip()
    if request.description is not None:
        updates["description"] = request.description.strip()
    if request.location is not None:
        updates["location"] = request.location.strip()
    if request.start_time is not None:
        updates["start_time"] = request.start_time
    if request.end_time is not None:
        updates["end_time"] = request.end_time
    if request.all_day is not None:
        updates["all_day"] = int(request.all_day)
    merged_start = str(updates.get("start_time", current.start_time))
    merged_end = str(updates.get("end_time", current.end_time))
    try:
        if datetime.fromisoformat(merged_end) < datetime.fromisoformat(merged_start):
            raise ValueError("end_time must be after start_time")
    except ValueError as exc:
        raise ValueError("start_time/end_time must be ISO datetimes") from exc
    updates["updated_at"] = _now_iso()
    if not updates:
        return current
    assignments = ", ".join(f"{key} = ?" for key in updates)
    with _connect() as conn:
        conn.execute(
            f"UPDATE calendar_events SET {assignments} WHERE id = ? AND user_id = ?",
            (*updates.values(), event_id, user_id),
        )
    return get_event(user_id, event_id)


def delete_event(user_id: str, event_id: str) -> bool:
    with _connect() as conn:
        cursor = conn.execute(
            "DELETE FROM calendar_events WHERE id = ? AND user_id = ?",
            (event_id, user_id),
        )
        return cursor.rowcount > 0


# ---------------------------------------------------------------------------
# Schedule extraction from email text (rule-based)
# ---------------------------------------------------------------------------

_MONTHS: dict[str, int] = {
    "jan": 1, "january": 1, "feb": 2, "february": 2,
    "mar": 3, "march": 3, "apr": 4, "april": 4, "may": 5,
    "jun": 6, "june": 6, "jul": 7, "july": 7, "aug": 8, "august": 8,
    "sep": 9, "sept": 9, "september": 9, "oct": 10, "october": 10,
    "nov": 11, "november": 11, "dec": 12, "december": 12,
}

_WEEKDAYS: dict[str, int] = {
    "monday": 0, "mon": 0, "tuesday": 1, "tue": 1, "tues": 1,
    "wednesday": 2, "wed": 2, "thursday": 3, "thu": 3, "thur": 3, "thurs": 3,
    "friday": 4, "fri": 4, "saturday": 5, "sat": 5, "sunday": 6, "sun": 6,
}

# ISO date: 2026-08-10
_RE_ISO_DATE = re.compile(r"\b(\d{4})-(\d{1,2})-(\d{1,2})\b")
# US date: 08/10/2026 or 8/10/26
_RE_US_DATE = re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b")
# Month day: "August 10" / "Aug 10, 2026" / "Aug 10th"
_RE_MONTH_DAY = re.compile(
    r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?"
    r"\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b",
    re.IGNORECASE,
)
# Day month: "10 August 2026" / "10th Aug"
_RE_DAY_MONTH = re.compile(
    r"\b(\d{1,2})(?:st|nd|rd|th)?\s+"
    r"(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?"
    r"(?:,?\s+(\d{4}))?\b",
    re.IGNORECASE,
)
# Times: 14:00, 2:00 PM, 2pm, 9:30am
_RE_TIME = re.compile(
    r"\b(\d{1,2}):(\d{2})\s*(am|pm|a\.m\.|p\.m\.)?\b|\b(\d{1,2})\s*(am|pm)\b",
    re.IGNORECASE,
)
# Location hints: "at LT19", "venue: Auditorium", "location: Room 2"
_RE_LOCATION = re.compile(
    r"\b(?:at|in|venue|location|place|room|where)\s*:?\s+"
    r"([A-Za-z0-9][A-Za-z0-9 .'\-]{1,59})",
    re.IGNORECASE,
)
# "to"/"-" between two times: "2:00 PM - 4:00 PM", "14:00 to 16:00"
_RE_TIME_RANGE = re.compile(r"\s*(?:-|to|until|–)\s*", re.IGNORECASE)

# Words that look like time phrases and should not be treated as locations.
_RE_TIME_PHRASE = re.compile(
    r"^\d{1,2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)?$|^\d{1,2}:\d{2}$",
    re.IGNORECASE,
)

# Skip subjects that carry no usable title on their own.
_RE_SUBJECT_PREFIX = re.compile(r"^(re|fwd|fw|答复|回复|转发)\s*:\s*", re.IGNORECASE)

# Emails are scanned for dates at most this far into the future.
_MAX_FUTURE_DAYS = 90
# Allow one day of slack into the past so late-night reminders still import.
_MAX_PAST_DAYS = 1


def _clean_title(subject: str) -> str:
    title = _RE_SUBJECT_PREFIX.sub("", subject).strip()
    if len(title) < 2 or title.lower() in {"reminder", "event", "calendar", "invitation"}:
        return ""
    return title[:120]


def _parse_date_token(sentence: str, base_date: date) -> date | None:
    """Resolve a single date reference from a sentence (best-effort)."""
    low = sentence.lower()
    if "today" in low:
        return base_date
    if "tomorrow" in low:
        return base_date + timedelta(days=1)
    match = _RE_ISO_DATE.search(sentence)
    if match:
        try:
            return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
        except ValueError:
            return None
    match = _RE_US_DATE.search(sentence)
    if match:
        month, day, year = int(match.group(1)), int(match.group(2)), int(match.group(3))
        if year < 100:
            year += 2000
        try:
            return date(year, month, day)
        except ValueError:
            return None
    match = _RE_MONTH_DAY.search(sentence)
    if match:
        month = _MONTHS[match.group(1).lower()[:3]]
        day = int(match.group(2))
        year = int(match.group(3)) if match.group(3) else base_date.year
        try:
            return date(year, month, day)
        except ValueError:
            return None
    match = _RE_DAY_MONTH.search(sentence)
    if match:
        day = int(match.group(1))
        month = _MONTHS[match.group(2).lower()[:3]]
        year = int(match.group(3)) if match.group(3) else base_date.year
        try:
            return date(year, month, day)
        except ValueError:
            return None
    # Weekday: "Monday", "Mon" -> next occurrence on/after base_date.
    for name, target in _WEEKDAYS.items():
        if re.search(rf"\b{name}\b", low):
            delta = (target - base_date.weekday()) % 7
            return base_date + timedelta(days=delta)
    return None


def _parse_time_tokens(sentence: str) -> list[tuple[int, int]]:
    """Return all ``(hour, minute)`` mentions in 24h form, in order."""
    results: list[tuple[int, int]] = []
    for match in _RE_TIME.finditer(sentence):
        if match.group(1) is not None:
            hour = int(match.group(1))
            minute = int(match.group(2))
            suffix = (match.group(3) or "").lower()
        else:
            hour = int(match.group(4))
            minute = 0
            suffix = (match.group(5) or "").lower()
        if "p" in suffix and hour < 12:
            hour += 12
        elif "a" in suffix and hour == 12:
            hour = 0
        if hour < 24 and minute < 60:
            results.append((hour, minute))
    return results


def _extract_location(sentence: str) -> str:
    for match in _RE_LOCATION.finditer(sentence):
        candidate = match.group(1).strip(" .:，,;；")
        if not candidate:
            continue
        if _RE_TIME_PHRASE.match(candidate):
            continue
        if len(candidate) >= 2:
            return candidate[:120]
    return ""


def _sentence_title(sentence: str, subject: str) -> str:
    """A readable title: the subject, or the sentence fragment before a date."""
    title = _clean_title(subject)
    if title:
        return title
    for pattern in (_RE_ISO_DATE, _RE_US_DATE, _RE_MONTH_DAY, _RE_DAY_MONTH):
        match = pattern.search(sentence)
        if match:
            fragment = sentence[: match.start()].strip(" :,;-–")
            if len(fragment) >= 3:
                return fragment[:120]
    return sentence.strip()[:120]


def parse_schedule(
    subject: str,
    body: str,
    *,
    base_date: date | None = None,
    message_id: str | None = None,
) -> list[ExtractedSchedule]:
    """Parse date/time/location hints out of one email into proposed schedules.

    Returns an empty list when nothing date-like is found. Dates must land
    within ``[_MAX_PAST_DAYS, _MAX_FUTURE_DAYS]`` of ``base_date`` to be kept.
    """
    base_date = base_date or date.today()
    results: list[ExtractedSchedule] = []
    seen_keys: set[str] = set()

    # Treat subject and each body line/sentence independently so a multi-event
    # email (e.g. a digest) yields several proposals.
    candidates = [subject, *re.split(r"[\n\r]+", body)]
    for raw in candidates:
        sentence = raw.strip()
        if not sentence or len(sentence) < 4:
            continue
        parsed_date = _parse_date_token(sentence, base_date)
        if parsed_date is None:
            continue
        # Sanity window: skip stale reminders and implausibly distant dates.
        if parsed_date < base_date - timedelta(days=_MAX_PAST_DAYS):
            continue
        if parsed_date > base_date + timedelta(days=_MAX_FUTURE_DAYS):
            continue

        times = _parse_time_tokens(sentence)
        location = _extract_location(sentence)
        title = _sentence_title(sentence, subject)
        description = sentence[:500]

        if not times:
            # Date-only mention -> all-day event.
            start = datetime.combine(parsed_date, datetime.min.time())
            end = start + timedelta(days=1)
            all_day = True
        else:
            start_hour, start_minute = times[0]
            start = datetime.combine(parsed_date, datetime.min.time()).replace(
                hour=start_hour, minute=start_minute
            )
            all_day = False
            if len(times) >= 2:
                # Two times in the same line: treat as a range.
                end_hour, end_minute = times[1]
                end = datetime.combine(parsed_date, datetime.min.time()).replace(
                    hour=end_hour, minute=end_minute
                )
            else:
                end = start + timedelta(hours=1)

        key = f"{message_id or ''}|{title.lower()}|{start.isoformat()}"
        if key in seen_keys:
            continue
        seen_keys.add(key)
        results.append(
            ExtractedSchedule(
                key=key,
                title=title,
                description=description,
                location=location,
                start_time=start.isoformat(),
                end_time=end.isoformat(),
                all_day=all_day,
                source_email_id=message_id,
                email_subject=subject,
            )
        )
    return results


def _extract_mode() -> str:
    """Resolve the extraction strategy from the environment (default ``auto``)."""
    mode = os.environ.get("MAIL_CALENDAR_EXTRACT_MODE", "auto").strip().lower()
    return mode if mode in ("auto", "llm", "rules") else "auto"


def extract_schedules_with_mode(
    messages: Iterable[MailMessage],
    base_date: date | None = None,
) -> tuple[list[ExtractedSchedule], str]:
    """Extract schedules and report which strategy produced them.

    Returns ``(schedules, mode)`` where ``mode`` is ``llm`` or ``rules``. In
    ``auto`` mode an LLM failure or empty result silently falls back to rules.
    """
    mode = _extract_mode()
    base_date = base_date or date.today()
    if mode in ("auto", "llm") and config.MAIL_LLM_API_KEY:
        try:
            llm_schedules = extract_schedules_with_llm(messages, base_date)
            if llm_schedules or mode == "llm":
                return llm_schedules, "llm"
        except Exception as exc:  # noqa: BLE001 - LLM must never break extraction
            logger.warning("LLM schedule extraction failed, using rules: %s", exc)
            if mode == "llm":
                raise
    return _extract_with_rules(messages, base_date), "rules"


def extract_schedules_from_messages(
    messages: Iterable[MailMessage],
    base_date: date | None = None,
) -> list[ExtractedSchedule]:
    """Extract schedules from messages: LLM first (when enabled), rules fallback.

    The strategy follows ``MAIL_CALENDAR_EXTRACT_MODE``:
      * ``auto`` (default) - LLM when configured; falls back to the rule parser
        on any LLM error or empty result;
      * ``llm`` - LLM only; an LLM failure is raised to the caller;
      * ``rules`` - always the built-in parser.
    """
    schedules, _mode = extract_schedules_with_mode(messages, base_date)
    return schedules


def _extract_with_rules(
    messages: Iterable[MailMessage],
    base_date: date | None,
) -> list[ExtractedSchedule]:
    """Parse every message with the rule parser and merge identical proposals."""
    merged: dict[tuple[str, str], ExtractedSchedule] = {}
    for message in messages:
        for schedule in parse_schedule(
            message.subject,
            message.body,
            base_date=base_date,
            message_id=message.id,
        ):
            dedupe_key = (schedule.title.lower(), schedule.start_time)
            merged[dedupe_key] = schedule
    return sorted(
        merged.values(), key=lambda schedule: schedule.start_time
    )


# ---------------------------------------------------------------------------
# LLM schedule extraction (DeepSeek, configured via MAIL_LLM_* / DEEPSEEK_*)
# ---------------------------------------------------------------------------

# Keep one LLM call cheap enough: truncate each email's body before prompting.
_LLM_MAX_BODY_CHARS = 1500
# Keep the whole batch to a sane token budget.
_LLM_MAX_MESSAGES = 15


class _LlmScheduleItem(BaseModel):
    """One schedule as returned by the LLM (keys mirror ExtractedSchedule)."""

    title: str | None = None
    description: str = ""
    location: str = ""
    start_time: str
    end_time: str | None = None
    all_day: bool = False


def _llm_extraction_prompt(messages: list[MailMessage], base_date: date) -> str:
    lines = [
        "You are a schedule extractor. From the emails below, find all concrete "
        "appointments, meetings, exams, deadlines and events (date+time or date-only).",
        f"Today's date is {base_date.isoformat()} ({base_date.strftime('%A')}).",
        "Rules:",
        "- Resolve relative dates (today, tomorrow, next Monday, 下周三, 明天, etc.) against today's date.",
        "- Only extract schedules that are explicitly stated; ignore marketing, reminders without a date, and quoted history.",
        "- For each schedule return one JSON object; dates/times as ISO 8601 (e.g. 2026-08-10T14:00:00).",
        "- EVERY object MUST include a short, specific \"title\" (the event name, e.g. \"CS2103 Final Exam\"). "
        "Never omit title, never use the email subject verbatim unless it is the event name, and never use placeholder text.",
        "- end_time: use the stated end if present, otherwise start + 1 hour (or + 1 day for all-day).",
        "- all_day: true when only a date is given (no time).",
        "- location: the venue/place if mentioned, otherwise empty string.",
        "- description: one short sentence quoting the schedule context.",
        "- Respond with ONLY a JSON array (no markdown, no commentary).",
        "- An email may contain zero, one or many schedules. Use email_index to say which email a schedule came from (1-based, matching the numbered emails below).",
        "",
    ]
    for index, message in enumerate(messages, start=1):
        body = " ".join(message.body.split())[:_LLM_MAX_BODY_CHARS]
        lines.append(f"Email {index}:")
        lines.append(f"Subject: {message.subject}")
        lines.append(f"Body: {body}")
        lines.append("")
    lines.append("JSON array:")
    return "\n".join(lines)


def _parse_llm_json(text: str) -> list[dict[str, Any]]:
    """Parse the LLM's JSON array, tolerating ```json fences and trailing text."""
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)
    start = cleaned.find("[")
    end = cleaned.rfind("]")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("LLM did not return a JSON array")
    payload = json.loads(cleaned[start : end + 1])
    if not isinstance(payload, list):
        raise ValueError("LLM response is not a JSON array")
    return [item for item in payload if isinstance(item, dict)]


def extract_schedules_with_llm(
    messages: Iterable[MailMessage],
    base_date: date | None = None,
) -> list[ExtractedSchedule]:
    """Ask the LLM to extract schedules from the given emails.

    Raises when the LLM is not configured or the call/parse fails; the caller
    decides whether to fall back to the rule parser.
    """
    if not config.MAIL_LLM_API_KEY:
        raise RuntimeError("MAIL_LLM_API_KEY is not configured")
    from langchain_openai import ChatOpenAI

    base_date = base_date or date.today()
    batch = list(messages)[:_LLM_MAX_MESSAGES]
    if not batch:
        return []

    llm = ChatOpenAI(
        model=config.MAIL_LLM_MODEL,
        api_key=config.MAIL_LLM_API_KEY,
        base_url=config.MAIL_LLM_BASE_URL,
        temperature=0,
        timeout=60,
        max_retries=1,
    )
    prompt = _llm_extraction_prompt(batch, base_date)
    response = llm.invoke(prompt)
    content = getattr(response, "content", "") or ""
    if isinstance(content, list):  # multi-part content -> join text parts
        content = " ".join(
            str(part.get("text", ""))
            for part in content
            if isinstance(part, dict) and part.get("type") == "text"
        )
    raw_items = _parse_llm_json(str(content))

    by_index = {index: message for index, message in enumerate(batch, start=1)}
    schedules: list[ExtractedSchedule] = []
    for item in raw_items:
        email_index = item.get("email_index")
        message = by_index.get(int(email_index)) if email_index is not None else None
        try:
            parsed = _LlmScheduleItem(**item)
        except Exception as exc:  # noqa: BLE001 - skip malformed entries
            logger.warning("Skipping malformed LLM schedule: %s (%s)", exc, item)
            continue
        start = _coerce_datetime(parsed.start_time)
        if start is None:
            continue
        # Sanity window (same as the rule parser).
        if start.date() < base_date - timedelta(days=_MAX_PAST_DAYS):
            continue
        if start.date() > base_date + timedelta(days=_MAX_FUTURE_DAYS):
            continue
        if parsed.all_day:
            end = datetime.combine(start.date() + timedelta(days=1), datetime.min.time())
        else:
            end = _coerce_datetime(parsed.end_time) if parsed.end_time else None
            if end is None or end <= start:
                end = start + timedelta(hours=1)
        title = (parsed.title or "").strip()[:120]
        if not title:
            # The model occasionally omits the title; fall back to the email
            # subject or a compact description so the proposal stays usable.
            title = (message.subject if message else "") or (parsed.description.strip()[:80])
        if not title:
            title = "Untitled schedule"
        schedules.append(
            ExtractedSchedule(
                key=f"llm|{message.id if message else ''}|{title.lower()}|{start.isoformat()}",
                title=title,
                description=parsed.description.strip()[:500],
                location=parsed.location.strip()[:120],
                start_time=start.isoformat(),
                end_time=end.isoformat(),
                all_day=parsed.all_day,
                source_email_id=message.id if message else None,
                email_subject=message.subject if message else "",
            )
        )
    return schedules


def _coerce_datetime(value: str) -> datetime | None:
    """Parse an ISO-ish datetime from the LLM, tolerating common variants."""
    text = value.strip().replace(" ", "T")
    for candidate in (text, text + ":00"):
        try:
            return datetime.fromisoformat(candidate)
        except ValueError:
            continue
    return None


# ---------------------------------------------------------------------------
# Import (after user confirmation)
# ---------------------------------------------------------------------------


def import_schedules(user_id: str, events: list[ExtractedSchedule]) -> ImportResponse:
    """Import confirmed extracted schedules, skipping ones already in the calendar.

    A schedule is considered already present when the calendar has an event with
    the same ``source_email_id`` and start time, or (for manual duplicates) the
    same title and start time.
    """
    imported: list[CalendarEvent] = []
    skipped = 0
    existing = list_events(user_id)
    existing_keys = {
        (event.source_email_id, event.start_time) for event in existing
    }
    existing_title_starts = {
        (event.title.lower(), event.start_time) for event in existing
    }
    now = _now_iso()
    with _connect() as conn:
        for schedule in events:
            start = schedule.start_time
            if not start:
                skipped += 1
                continue
            if (schedule.source_email_id, start) in existing_keys:
                skipped += 1
                continue
            if (schedule.title.strip().lower(), start) in existing_title_starts:
                skipped += 1
                continue
            event = CalendarEvent(
                id=uuid.uuid4().hex,
                user_id=user_id,
                title=schedule.title.strip(),
                description=schedule.description.strip(),
                location=schedule.location.strip(),
                start_time=start,
                end_time=schedule.end_time,
                all_day=schedule.all_day,
                source="mail",
                source_email_id=schedule.source_email_id,
                created_at=now,
                updated_at=now,
            )
            conn.execute(
                """
                INSERT INTO calendar_events (
                    id, user_id, title, description, location, start_time, end_time,
                    all_day, source, source_email_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event.id, user_id, event.title, event.description, event.location,
                    event.start_time, event.end_time, int(event.all_day),
                    event.source, event.source_email_id, event.created_at,
                    event.updated_at,
                ),
            )
            existing_keys.add((event.source_email_id, event.start_time))
            existing_title_starts.add((event.title.lower(), event.start_time))
            imported.append(event)
    return ImportResponse(
        imported=len(imported), skipped=skipped, events=imported
    )
