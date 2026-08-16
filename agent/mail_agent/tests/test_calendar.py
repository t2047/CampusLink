"""Tests for the calendar service: SQLite CRUD, schedule extraction parser,
the /api/mail/calendar/** endpoints, and the confirmed import flow."""

from __future__ import annotations

import json
from datetime import date, datetime, timezone

import pytest
from fastapi.testclient import TestClient

from mail_agent import calendar_service, config, gmail_service
from mail_agent.calendar_service import (
    CalendarEventRequest,
    CalendarEventUpdate,
    ExtractedSchedule,
)
from mail_agent.main import app
from mail_agent.models import MailFolder, MailMessage

from . import helpers

client = TestClient(app)
AUTH = helpers.auth_header(helpers.user_jwt("user-1@campuslink.test"))
AUTH2 = helpers.auth_header(helpers.user_jwt("user-2@campuslink.test"))


@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    """Point the calendar at a throwaway SQLite file for every test.

    Also forces ``rules`` extraction mode so the deterministic tests never hit
    the real LLM; LLM-path tests opt in explicitly via monkeypatch.
    """
    monkeypatch.setattr(calendar_service, "CALENDAR_DB_PATH", tmp_path / "test.db")
    monkeypatch.setenv("MAIL_CALENDAR_EXTRACT_MODE", "rules")
    yield
    # Drop all rows so tests never leak state into each other.
    with calendar_service._connect() as conn:
        conn.execute("DELETE FROM calendar_events")


def make_message(**overrides) -> MailMessage:
    defaults: dict = {
        "id": "msg-1",
        "subject": "CS2103 Final Exam",
        "sender": "prof@nus.edu.sg",
        "recipients": ["me@u.nus.edu"],
        "preview": "The exam will be held on 2026-08-10 at 14:00.",
        "body": "The exam will be held on 2026-08-10 at 14:00 in LT19.",
        "folder": MailFolder.inbox,
        "read": False,
        "starred": False,
        "created_at": datetime(2026, 8, 6, tzinfo=timezone.utc),
        "updated_at": datetime(2026, 8, 6, tzinfo=timezone.utc),
    }
    defaults.update(overrides)
    return MailMessage(**defaults)


# ---------------------------------------------------------------------------
# Schedule extraction parser
# ---------------------------------------------------------------------------

BASE = date(2026, 8, 6)


class TestParseSchedule:
    def test_iso_date_with_time_and_location(self):
        schedules = calendar_service.parse_schedule(
            "CS2103 Final Exam",
            "The exam will be held on 2026-08-10 at 14:00 in LT19.",
            base_date=BASE,
        )
        assert len(schedules) == 1
        schedule = schedules[0]
        assert schedule.title == "CS2103 Final Exam"
        assert schedule.start_time == "2026-08-10T14:00:00"
        assert schedule.end_time == "2026-08-10T15:00:00"
        assert schedule.location == "LT19"

    def test_month_day_with_range(self):
        schedules = calendar_service.parse_schedule(
            "Career Fair",
            "Join us on August 15 from 2:00 PM to 5:00 PM at MPSH 1.",
            base_date=BASE,
        )
        assert len(schedules) == 1
        schedule = schedules[0]
        assert schedule.start_time == "2026-08-15T14:00:00"
        assert schedule.end_time == "2026-08-15T17:00:00"
        assert schedule.location == "MPSH 1"

    def test_tomorrow_relative(self):
        schedules = calendar_service.parse_schedule(
            "Meeting", "Reminder: project meeting tomorrow at 9am.", base_date=BASE
        )
        assert len(schedules) == 1
        assert schedules[0].start_time == "2026-08-07T09:00:00"

    def test_all_day_when_no_time(self):
        schedules = calendar_service.parse_schedule(
            "Report Due", "The report is due on 2026-08-20.", base_date=BASE
        )
        assert len(schedules) == 1
        assert schedules[0].all_day is True
        assert schedules[0].start_time == "2026-08-20T00:00:00"

    def test_ignores_out_of_window_dates(self):
        schedules = calendar_service.parse_schedule(
            "Old News", "This happened on 2020-01-01.", base_date=BASE
        )
        assert schedules == []

    def test_multi_event_digest(self):
        body = (
            "Event 1: 2026-08-10 at 10:00\n"
            "Event 2: 2026-08-12 at 15:30\n"
            "No date here.\n"
        )
        schedules = calendar_service.parse_schedule(
            "Weekly Digest", body, base_date=BASE
        )
        assert len(schedules) == 2

    def test_dedupe_identical_proposals_across_messages(self):
        first = make_message(id="msg-1", body="Exam on 2026-08-10 at 14:00")
        second = make_message(id="msg-2", body="Exam on 2026-08-10 at 14:00")
        schedules = calendar_service.extract_schedules_from_messages(
            [first, second], base_date=BASE
        )
        assert len(schedules) == 1


# ---------------------------------------------------------------------------
# CRUD
# ---------------------------------------------------------------------------


class TestCalendarCrud:
    def test_create_list_get(self):
        created = client.post(
            "/api/mail/calendar/events",
            headers=AUTH,
            json={
                "title": "Tutorial",
                "description": "Week 8",
                "location": "COM1",
                "start_time": "2026-08-10T10:00:00",
                "end_time": "2026-08-10T11:00:00",
            },
        )
        assert created.status_code == 201
        event = created.json()
        assert event["title"] == "Tutorial"
        assert event["source"] == "manual"

        listed = client.get("/api/mail/calendar/events", headers=AUTH).json()
        assert [item["id"] for item in listed] == [event["id"]]

        fetched = client.get(
            f"/api/mail/calendar/events/{event['id']}", headers=AUTH
        ).json()
        assert fetched["location"] == "COM1"

    def test_events_are_per_user(self):
        first = client.post(
            "/api/mail/calendar/events",
            headers=AUTH,
            json={
                "title": "Mine",
                "start_time": "2026-08-10T10:00:00",
                "end_time": "2026-08-10T11:00:00",
            },
        ).json()
        other = client.post(
            "/api/mail/calendar/events",
            headers=AUTH2,
            json={
                "title": "Theirs",
                "start_time": "2026-08-11T10:00:00",
                "end_time": "2026-08-11T11:00:00",
            },
        ).json()
        assert client.get("/api/mail/calendar/events", headers=AUTH).json() == [first]
        assert (
            client.get(
                "/api/mail/calendar/events", headers=AUTH2
            ).json()
            == [other]
        )

    def test_update_and_delete(self):
        created = client.post(
            "/api/mail/calendar/events",
            headers=AUTH,
            json={
                "title": "Draft",
                "start_time": "2026-08-10T10:00:00",
                "end_time": "2026-08-10T11:00:00",
            },
        ).json()
        updated = client.patch(
            f"/api/mail/calendar/events/{created['id']}",
            headers=AUTH,
            json={"title": "Renamed", "location": "LT19"},
        ).json()
        assert updated["title"] == "Renamed"
        assert updated["location"] == "LT19"

        deleted = client.delete(
            f"/api/mail/calendar/events/{created['id']}", headers=AUTH
        )
        assert deleted.status_code == 204
        assert client.get("/api/mail/calendar/events", headers=AUTH).json() == []

    def test_delete_other_users_event_404(self):
        created = client.post(
            "/api/mail/calendar/events",
            headers=AUTH2,
            json={
                "title": "Private",
                "start_time": "2026-08-10T10:00:00",
                "end_time": "2026-08-10T11:00:00",
            },
        ).json()
        response = client.delete(
            f"/api/mail/calendar/events/{created['id']}", headers=AUTH
        )
        assert response.status_code == 404

    def test_validation_errors(self):
        bad_range = client.post(
            "/api/mail/calendar/events",
            headers=AUTH,
            json={
                "title": "Bad",
                "start_time": "2026-08-10T12:00:00",
                "end_time": "2026-08-10T11:00:00",
            },
        )
        assert bad_range.status_code == 422
        missing_title = client.post(
            "/api/mail/calendar/events",
            headers=AUTH,
            json={
                "title": "",
                "start_time": "2026-08-10T12:00:00",
                "end_time": "2026-08-10T13:00:00",
            },
        )
        assert missing_title.status_code == 422

    def test_range_filter(self):
        for day in (10, 11, 12):
            client.post(
                "/api/mail/calendar/events",
                headers=AUTH,
                json={
                    "title": f"Event {day}",
                    "start_time": f"2026-08-{day}T10:00:00",
                    "end_time": f"2026-08-{day}T11:00:00",
                },
            )
        listed = client.get(
            "/api/mail/calendar/events",
            headers=AUTH,
            params={"start": "2026-08-11T00:00:00", "end": "2026-08-12T00:00:00"},
        ).json()
        assert [item["title"] for item in listed] == ["Event 11"]


# ---------------------------------------------------------------------------
# Import flow (extract -> confirm -> import)
# ---------------------------------------------------------------------------


class TestImport:
    def test_extract_endpoint_uses_recent_messages(self, monkeypatch):
        today = date.today()
        message = make_message(
            body=f"The exam will be held on {today.isoformat()} at 14:00 in LT19."
        )
        monkeypatch.setattr(
            gmail_service,
            "list_recent_messages",
            lambda user_id, days, max_results=20: [message],
        )
        response = client.post(
            "/api/mail/calendar/extract",
            headers=AUTH,
            params={"days": 1},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["scanned"] == 1
        assert body["days"] == 1
        assert len(body["events"]) == 1
        assert body["events"][0]["source_email_id"] == "msg-1"

    def test_extract_requires_gmail(self, monkeypatch):
        def raise_not_connected(*_args, **_kwargs):
            raise gmail_service.GmailNotConnectedError("nope")

        monkeypatch.setattr(gmail_service, "list_recent_messages", raise_not_connected)
        response = client.post("/api/mail/calendar/extract", headers=AUTH)
        assert response.status_code == 409
        assert response.json()["code"] == "GMAIL_NOT_CONNECTED"

    def test_import_creates_and_dedupes(self):
        schedule = ExtractedSchedule(
            key="k1",
            title="Exam",
            description="desc",
            location="LT19",
            start_time="2026-08-10T14:00:00",
            end_time="2026-08-10T15:00:00",
            source_email_id="msg-1",
            email_subject="CS2103 Final Exam",
        )
        first = client.post(
            "/api/mail/calendar/import",
            headers=AUTH,
            json={"events": [schedule.model_dump()]},
        )
        assert first.status_code == 200
        assert first.json()["imported"] == 1
        assert first.json()["skipped"] == 0

        # Same email + start time again -> skipped.
        second = client.post(
            "/api/mail/calendar/import",
            headers=AUTH,
            json={"events": [schedule.model_dump()]},
        )
        assert second.json()["imported"] == 0
        assert second.json()["skipped"] == 1

        events = client.get("/api/mail/calendar/events", headers=AUTH).json()
        assert len(events) == 1
        assert events[0]["source"] == "mail"
        assert events[0]["source_email_id"] == "msg-1"

    def test_import_dedupe_within_same_payload(self):
        payload = [
            ExtractedSchedule(
                key="k1",
                title="Workshop",
                start_time="2026-08-12T10:00:00",
                end_time="2026-08-12T11:00:00",
                source_email_id="msg-1",
            ).model_dump(),
            ExtractedSchedule(
                key="k2",
                title="Workshop",
                start_time="2026-08-12T10:00:00",
                end_time="2026-08-12T11:00:00",
                source_email_id="msg-2",
            ).model_dump(),
        ]
        # Both share title+start; the second must be skipped (dedupe within payload
        # via the already-inserted keys in this run of import_schedules).
        response = client.post("/api/mail/calendar/import", headers=AUTH, json={"events": payload})
        assert response.json()["imported"] == 1
        assert response.json()["skipped"] == 1

    def test_manual_crud_helpers(self):
        request = CalendarEventRequest(
            title="Standup",
            start_time="2026-08-13T09:00:00",
            end_time="2026-08-13T09:15:00",
        )
        event = calendar_service.create_event("user-1", request)
        assert event.title == "Standup"

        updated = calendar_service.update_event(
            "user-1", event.id, CalendarEventUpdate(title="Standup moved")
        )
        assert updated is not None
        assert updated.title == "Standup moved"

        assert calendar_service.delete_event("user-1", event.id) is True
        assert calendar_service.get_event("user-1", event.id) is None


# ---------------------------------------------------------------------------
# LLM extraction path (DeepSeek), with the chat call mocked
# ---------------------------------------------------------------------------


class FakeLlmResponse:
    def __init__(self, content: str) -> None:
        self.content = content


class TestLlmExtraction:
    def test_parse_llm_json_tolerates_fences(self):
        text = "```json\n[{\"title\": \"Exam\", \"start_time\": \"2026-08-10T14:00:00\"}]\n```"
        items = calendar_service._parse_llm_json(text)
        assert items == [{"title": "Exam", "start_time": "2026-08-10T14:00:00"}]

    def test_parse_llm_json_rejects_non_array(self):
        with pytest.raises(ValueError):
            calendar_service._parse_llm_json("just some text")

    def test_coerce_datetime_variants(self):
        assert calendar_service._coerce_datetime("2026-08-10T14:00:00") is not None
        assert calendar_service._coerce_datetime("2026-08-10 14:00") is not None
        assert calendar_service._coerce_datetime("2026-08-10") is not None
        assert calendar_service._coerce_datetime("garbage") is None

    def test_llm_extraction_maps_email_index(self, monkeypatch):
        monkeypatch.setenv("MAIL_CALENDAR_EXTRACT_MODE", "llm")
        message = make_message(id="msg-9", body="Exam on 2026-08-10 at 14:00 in LT19")
        payload = json.dumps([
            {
                "email_index": 1,
                "title": "CS2103 Final Exam",
                "start_time": "2026-08-10T14:00:00",
                "end_time": "2026-08-10T16:00:00",
                "location": "LT19",
                "all_day": False,
                "description": "The exam is on 2026-08-10.",
            }
        ])

        class FakeLlm:
            def invoke(self, prompt: str) -> FakeLlmResponse:
                assert "Email 1:" in prompt
                assert "2026-08-10" in prompt
                return FakeLlmResponse(payload)

        monkeypatch.setattr(
            "langchain_openai.ChatOpenAI",
            lambda **kwargs: FakeLlm(),
        )
        schedules = calendar_service.extract_schedules_with_llm([message], base_date=BASE)
        assert len(schedules) == 1
        schedule = schedules[0]
        assert schedule.title == "CS2103 Final Exam"
        assert schedule.start_time == "2026-08-10T14:00:00"
        assert schedule.end_time == "2026-08-10T16:00:00"
        assert schedule.location == "LT19"
        assert schedule.source_email_id == "msg-9"

    def test_llm_all_day_default_end(self, monkeypatch):
        message = make_message(id="msg-9", body="Report due 2026-08-20")
        payload = json.dumps([
            {
                "email_index": 1,
                "title": "Report Due",
                "start_time": "2026-08-20T00:00:00",
                "all_day": True,
            }
        ])

        class FakeLlm:
            def invoke(self, prompt: str) -> FakeLlmResponse:
                return FakeLlmResponse(payload)

        monkeypatch.setattr(
            "langchain_openai.ChatOpenAI",
            lambda **kwargs: FakeLlm(),
        )
        schedules = calendar_service.extract_schedules_with_llm([message], base_date=BASE)
        assert len(schedules) == 1
        assert schedules[0].all_day is True
        assert schedules[0].end_time == "2026-08-21T00:00:00"

    def test_llm_missing_title_falls_back_to_subject(self, monkeypatch):
        message = make_message(id="msg-9", body="Exam on 2026-08-10 at 14:00")
        payload = json.dumps([
            {
                "email_index": 1,
                "start_time": "2026-08-10T14:00:00",
                "all_day": False,
            }
        ])

        class FakeLlm:
            def invoke(self, prompt: str) -> FakeLlmResponse:
                return FakeLlmResponse(payload)

        monkeypatch.setattr("langchain_openai.ChatOpenAI", lambda **kwargs: FakeLlm())
        schedules = calendar_service.extract_schedules_with_llm([message], base_date=BASE)
        assert len(schedules) == 1
        assert schedules[0].title == "CS2103 Final Exam"
        assert schedules[0].source_email_id == "msg-9"

    def test_extract_schedules_with_mode_uses_llm_when_configured(self, monkeypatch):
        monkeypatch.setenv("MAIL_CALENDAR_EXTRACT_MODE", "llm")
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")
        message = make_message(id="msg-9", body="Exam on 2026-08-10 at 14:00")
        payload = json.dumps([
            {
                "email_index": 1,
                "title": "Exam",
                "start_time": "2026-08-10T14:00:00",
                "all_day": False,
            }
        ])

        class FakeLlm:
            def invoke(self, prompt: str) -> FakeLlmResponse:
                return FakeLlmResponse(payload)

        monkeypatch.setattr("langchain_openai.ChatOpenAI", lambda **kwargs: FakeLlm())
        schedules, mode = calendar_service.extract_schedules_with_mode(
            [message], base_date=BASE
        )
        assert mode == "llm"
        assert len(schedules) == 1
        assert schedules[0].source_email_id == "msg-9"

    def test_llm_failure_falls_back_to_rules_in_auto_mode(self, monkeypatch):
        monkeypatch.setenv("MAIL_CALENDAR_EXTRACT_MODE", "auto")
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def boom(*_args, **_kwargs):
            raise RuntimeError("LLM down")

        monkeypatch.setattr(
            calendar_service, "extract_schedules_with_llm", boom
        )
        message = make_message(id="msg-9", body="Exam on 2026-08-10 at 14:00 in LT19")
        schedules, mode = calendar_service.extract_schedules_with_mode(
            [message], base_date=BASE
        )
        assert mode == "rules"
        assert len(schedules) == 1
        assert schedules[0].title == "CS2103 Final Exam"

    def test_extract_endpoint_reports_mode(self, monkeypatch):
        monkeypatch.setenv("MAIL_CALENDAR_EXTRACT_MODE", "rules")
        today = date.today()
        message = make_message(
            body=f"The exam will be held on {today.isoformat()} at 14:00 in LT19."
        )
        monkeypatch.setattr(
            gmail_service,
            "list_recent_messages",
            lambda user_id, days, max_results=20: [message],
        )
        response = client.post("/api/mail/calendar/extract", headers=AUTH, params={"days": 1})
        assert response.status_code == 200
        body = response.json()
        assert body["mode"] == "rules"
