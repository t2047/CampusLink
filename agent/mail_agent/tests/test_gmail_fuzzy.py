"""Tests for fuzzy mail search (Gmail OR prefilter + local scoring)."""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from mail_agent import classifier, gmail_service
from mail_agent.models import MailFolder, MailMessage


@pytest.fixture()
def no_classify(monkeypatch):
    """Keep classification out of gmail tests (no LLM/ML calls in unit tests)."""
    monkeypatch.setattr(classifier, "classify_many", lambda records: {})


def make_message(**overrides) -> MailMessage:
    defaults: dict = {
        "id": "msg-1",
        "subject": "Exam Reminder",
        "sender": "prof@nus.edu.sg",
        "recipients": ["me@u.nus.edu"],
        "preview": "Reminder: the exam starts at 9am.",
        "body": "Reminder: the exam starts at 9am.",
        "folder": MailFolder.inbox,
        "read": False,
        "starred": False,
        "created_at": datetime(2026, 8, 1, tzinfo=timezone.utc),
        "updated_at": datetime(2026, 8, 1, tzinfo=timezone.utc),
    }
    defaults.update(overrides)
    return MailMessage(**defaults)


class TestFuzzyScore:
    def test_empty_query_scores_full(self):
        assert gmail_service._fuzzy_score("", "any", "any", "any") == 1.0

    def test_substring_match_scores_full(self):
        assert gmail_service._fuzzy_score("exam", "Exam Reminder", "prof@nus.edu.sg", "") == 1.0

    def test_sender_substring_match(self):
        assert gmail_service._fuzzy_score("prof", "Anything", "Prof. Lee <prof@nus.edu.sg>", "") == 1.0

    def test_partial_token_coverage(self):
        # "exam reminder" 只有 exam 命中 -> 0.5
        score = gmail_service._fuzzy_score(
            "exam reminder", "Exam Cancelled", "prof@nus.edu.sg", ""
        )
        assert score == 0.5

    def test_typo_tolerance(self):
        # exma -> exam 编辑距离相似度 ~0.75
        score = gmail_service._fuzzy_score("exma", "Exam Reminder", "prof@nus.edu.sg", "")
        assert score >= 0.5

    def test_unrelated_query_scores_low(self):
        score = gmail_service._fuzzy_score("football", "Exam Reminder", "prof@nus.edu.sg", "No matches here")
        assert score < gmail_service._FUZZY_MIN_SCORE


class TestFuzzyCandidateIds:
    def test_or_prefilter_uses_tokenized_query(self, monkeypatch):
        calls: list[str] = []

        def fake_list_page(service, size, query, label_ids, token):
            calls.append(query)
            return {"messages": [{"id": "a"}, {"id": "b"}], "nextPageToken": None}

        monkeypatch.setattr(gmail_service, "_list_page", fake_list_page)
        ids = gmail_service._fuzzy_candidate_ids(
            object(), MailFolder.inbox, "exam reminder", None, None, ["INBOX"]
        )
        assert ids == ["a", "b"]
        assert calls[0] == "exam OR reminder"

    def test_fallback_to_recent_when_or_returns_nothing(self, monkeypatch):
        def fake_list_page(service, size, query, label_ids, token):
            if "OR" in query:
                return {"messages": [], "nextPageToken": None}
            return {"messages": [{"id": "fallback-1"}], "nextPageToken": None}

        monkeypatch.setattr(gmail_service, "_list_page", fake_list_page)
        ids = gmail_service._fuzzy_candidate_ids(
            object(), MailFolder.inbox, "考试通知", None, None, ["INBOX"]
        )
        assert ids == ["fallback-1"]


class TestFuzzySearch:
    def _patch_fetch(self, monkeypatch, messages: list[MailMessage]):
        by_id = {message.id: message for message in messages}

        def fake_candidate_ids(*args, **kwargs):
            return list(by_id)

        def fake_fetch_metadata(service, ids, classify=True):
            return [by_id[mid] for mid in ids if mid in by_id]

        monkeypatch.setattr(gmail_service, "_fuzzy_candidate_ids", fake_candidate_ids)
        monkeypatch.setattr(gmail_service, "_fetch_metadata", fake_fetch_metadata)

    def test_ranks_and_filters_by_score(self, monkeypatch, no_classify):
        messages = [
            make_message(id="old", subject="Exam Reminder", created_at=datetime(2026, 7, 1, tzinfo=timezone.utc)),
            make_message(
                id="career",
                subject="Career Fair",
                preview="Join us at the career fair on campus.",
                created_at=datetime(2026, 7, 2, tzinfo=timezone.utc),
            ),
            make_message(id="new", subject="EXAM", preview="exam time", created_at=datetime(2026, 7, 3, tzinfo=timezone.utc)),
        ]
        self._patch_fetch(monkeypatch, messages)
        page, total, has_next = gmail_service._fuzzy_search(
            object(), MailFolder.inbox, "exam", None, None, ["INBOX"], 0, 50
        )
        assert total == 2
        assert [message.id for message in page] == ["new", "old"]
        assert has_next is False

    def test_pagination(self, monkeypatch, no_classify):
        messages = [
            make_message(id="m1", subject="Exam A", created_at=datetime(2026, 7, 2, tzinfo=timezone.utc)),
            make_message(id="m2", subject="Exam B", created_at=datetime(2026, 7, 1, tzinfo=timezone.utc)),
        ]
        self._patch_fetch(monkeypatch, messages)
        page, total, has_next = gmail_service._fuzzy_search(
            object(), MailFolder.inbox, "exam", None, None, ["INBOX"], 1, 1
        )
        assert total == 2
        assert [message.id for message in page] == ["m2"]
        assert has_next is False


class TestListMessagesRouting:
    def test_fuzzy_route_for_natural_query(self, monkeypatch):
        monkeypatch.setattr(gmail_service, "_service", lambda user_id: object())
        monkeypatch.setattr(
            gmail_service,
            "_fuzzy_search",
            lambda *args, **kwargs: ([], 0, False),
        )
        messages, total, _has_next = gmail_service.list_messages(
            "user-1", MailFolder.inbox, q="exam"
        )
        assert messages == []
        assert total == 0

    def test_exact_route_for_gmail_syntax(self, monkeypatch):
        monkeypatch.setattr(gmail_service, "_service", lambda user_id: object())

        def fail_fuzzy(*args, **kwargs):  # pragma: no cover
            raise AssertionError("fuzzy search should not run for Gmail syntax")

        monkeypatch.setattr(gmail_service, "_fuzzy_search", fail_fuzzy)
        monkeypatch.setattr(
            gmail_service,
            "_fetch_page_ids",
            lambda service, key, page, size, query, label_ids: (["m1"], 1, False),
        )
        monkeypatch.setattr(
            gmail_service,
            "_fetch_metadata",
            lambda service, ids: [make_message(id="m1")],
        )
        messages, total, _has_next = gmail_service.list_messages(
            "user-1", MailFolder.inbox, q="from:prof@nus.edu.sg"
        )
        assert [message.id for message in messages] == ["m1"]
        assert total == 1


class TestDateRangeFilter:
    def test_date_arg_to_utc_bound(self):
        # Local 2026-08-01 00:00 -> UTC (UTC+8 => previous day 16:00).
        bound = gmail_service._date_arg_to_utc_bound("2026-08-01")
        assert bound is not None
        assert bound.hour in (16, 0)  # depends on host TZ; must be a UTC datetime
        assert bound.tzinfo is not None

    def test_date_arg_to_utc_bound_end_of_day(self):
        bound = gmail_service._date_arg_to_utc_bound("2026-08-01", end_of_day=True)
        assert bound is not None
        assert bound.hour in (15, 23)  # 23:59 local -> UTC
        assert bound.tzinfo is not None

    def test_date_arg_to_utc_bound_empty(self):
        assert gmail_service._date_arg_to_utc_bound("") is None
        assert gmail_service._date_arg_to_utc_bound(None) is None

    def test_date_range_filters_by_received_date(self, monkeypatch, no_classify):
        monkeypatch.setattr(gmail_service, "_service", lambda user_id: object())
        # msg-1 received 2026-08-01 02:00 UTC; msg-2 received 2026-08-05 02:00 UTC.
        messages = [
            make_message(
                id="msg-1",
                subject="Forwarded exam",
                created_at=datetime(2026, 8, 1, 2, 0, tzinfo=timezone.utc),
            ),
            make_message(
                id="msg-2",
                subject="Later mail",
                created_at=datetime(2026, 8, 5, 2, 0, tzinfo=timezone.utc),
            ),
        ]
        monkeypatch.setattr(
            gmail_service,
            "_fetch_recent_ids",
            lambda service, query, label_ids, max_results=500: ["msg-1", "msg-2"],
        )
        monkeypatch.setattr(
            gmail_service,
            "_fetch_metadata",
            lambda service, ids, classify=True: [
                message for message in messages if message.id in ids
            ],
        )
        # after=2026-08-01 (local day start) should include msg-1 (received 02:00 UTC).
        page, total, _has_next = gmail_service.list_messages(
            "user-1", MailFolder.inbox, after="2026-08-01", before="2026-08-02"
        )
        assert total == 1
        assert [message.id for message in page] == ["msg-1"]

    def test_date_range_captures_forwarded_mail(self, monkeypatch, no_classify):
        """A message sent days earlier but received today must match 'today'."""
        monkeypatch.setattr(gmail_service, "_service", lambda user_id: object())
        today = datetime.now().astimezone()
        sent_earlier = datetime(
            today.year, today.month, today.day, 2, 0
        ).astimezone(timezone.utc)
        messages = [
            make_message(
                id="fwd-1",
                subject="Forwarded",
                created_at=sent_earlier,
            ),
        ]
        monkeypatch.setattr(
            gmail_service,
            "_fetch_recent_ids",
            lambda service, query, label_ids, max_results=500: ["fwd-1"],
        )
        monkeypatch.setattr(
            gmail_service,
            "_fetch_metadata",
            lambda service, ids, classify=True: [
                message for message in messages if message.id in ids
            ],
        )
        today_key = today.strftime("%Y-%m-%d")
        page, total, _has_next = gmail_service.list_messages(
            "user-1", MailFolder.inbox, after=today_key
        )
        assert total == 1
        assert page[0].id == "fwd-1"


class TestSpamFolder:
    def test_folder_from_labels_spam(self):
        assert gmail_service._folder_from_labels(["SPAM"]) == MailFolder.spam
        assert gmail_service._folder_from_labels(["INBOX"]) == MailFolder.inbox

    def test_folder_label_ids_spam(self):
        assert gmail_service._folder_label_ids(MailFolder.spam) == ["SPAM"]

    def test_trash_messages_batches(self, monkeypatch):
        class FakeService:
            def __init__(self):
                self.batches: list[list] = []

            def new_batch_http_request(self, callback=None):
                return FakeBatch(self.batches)

            def users(self):
                return self

            def messages(self):
                return self

            def trash(self, userId="me", id=""):
                return {"message_id": id}

        class FakeBatch:
            def __init__(self, batches: list[list]):
                self.batches = batches
                self.items: list = []

            def add(self, request, request_id=None):
                self.items.append(request)

            def execute(self):
                self.batches.append([item for item in self.items])

        service = FakeService()
        monkeypatch.setattr(gmail_service, "_service", lambda user_id: service)
        done = gmail_service.trash_messages("user-1", [f"id-{i}" for i in range(25)])
        assert done == 25
        assert len(service.batches) == 3  # 10 + 10 + 5 chunks
        assert sum(len(batch) for batch in service.batches) == 25
