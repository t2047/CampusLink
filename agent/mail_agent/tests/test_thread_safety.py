"""Tests for the Gmail API serialization lock.

The shared httplib2 transport is not thread-safe; LangGraph runs sync tools in
a thread pool and parallel tool calls crashed the process with a native access
violation inside ssl/httplib2. These tests verify that concurrent calls to the
public Gmail functions are serialized (never overlap) through ``_api_lock``.
"""

from __future__ import annotations

import threading
import time
from datetime import datetime, timezone

from mail_agent import gmail_service
from mail_agent.models import MailFolder, MailMessage


def make_message(**overrides) -> MailMessage:
    defaults: dict = {
        "id": "msg-1",
        "subject": "Exam Reminder",
        "sender": "prof@nus.edu.sg",
        "recipients": ["me@u.nus.edu"],
        "preview": "Reminder.",
        "body": "Reminder.",
        "folder": MailFolder.inbox,
        "read": False,
        "starred": False,
        "created_at": datetime(2026, 8, 1, tzinfo=timezone.utc),
        "updated_at": datetime(2026, 8, 1, tzinfo=timezone.utc),
    }
    defaults.update(overrides)
    return MailMessage(**defaults)


class TestSerializationLock:
    def test_trash_messages_is_serialized(self, monkeypatch):
        """Concurrent trash calls must not interleave on the shared client."""
        active = 0
        max_active = 0
        active_lock = threading.Lock()

        class FakeService:
            def new_batch_http_request(self, callback=None):
                return FakeBatch()

            def users(self):
                return self

            def messages(self):
                return self

            def trash(self, userId="me", id=""):
                return {"message_id": id}

        class FakeBatch:
            def __init__(self):
                self.items: list = []

            def add(self, request, request_id=None):
                self.items.append(request)

            def execute(self):
                nonlocal active, max_active
                with active_lock:
                    active += 1
                    max_active = max(max_active, active)
                time.sleep(0.01)  # widen the race window
                with active_lock:
                    active -= 1

        monkeypatch.setattr(gmail_service, "_service", lambda: FakeService())

        results: list[int] = []

        def worker(ids: list[str]) -> None:
            results.append(gmail_service.trash_messages(ids))

        threads = [
            threading.Thread(target=worker, args=([f"id-{i}" for i in range(5)],))
            for _ in range(6)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        assert sum(results) == 30
        # No two batch executions ever overlapped thanks to the lock.
        assert max_active == 1

    def test_list_messages_is_serialized(self, monkeypatch):
        active = 0
        max_active = 0
        active_lock = threading.Lock()
        calls: list[str] = []

        class FakeService:
            def new_batch_http_request(self, callback=None):
                return object()

            def users(self):
                return self

            def messages(self):
                return self

            def list(self, userId="me", maxResults=50, q=None, labelIds=None, pageToken=None):
                return {"messages": [], "resultSizeEstimate": 0}

        class FakeRequest:
            def execute(self):
                return {"messages": [], "resultSizeEstimate": 0}

        monkeypatch.setattr(gmail_service, "_service", lambda: FakeService())
        monkeypatch.setattr(gmail_service, "_list_page", lambda *args, **kwargs: FakeRequest().execute())

        def fake_fuzzy_search(*args, **kwargs):
            nonlocal active, max_active
            with active_lock:
                active += 1
                max_active = max(max_active, active)
            time.sleep(0.01)
            with active_lock:
                active -= 1
            return [], 0, False

        monkeypatch.setattr(gmail_service, "_fuzzy_search", fake_fuzzy_search)

        def worker() -> None:
            gmail_service.list_messages(MailFolder.inbox, q="exam", page=0, size=5)
            calls.append("done")

        threads = [threading.Thread(target=worker) for _ in range(5)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        assert len(calls) == 5
        assert max_active == 1
