"""Tests for the LangChain mail agent: tool resolution, tool calls, chat runner
and the /api/mail/agent/chat endpoint (all Gmail/LLM calls are mocked)."""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage

from mail_agent import agent
from mail_agent.main import app
from mail_agent.models import MailFolder, MailMessage


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


def _list_returning(message: MailMessage | None):
    def fake(
        folder, q="", unread=None, starred=None,
        after=None, before=None, page=0, size=10,
    ):
        return (
            [message] if message else [],
            1 if message else 0,
            False,
        )
    return fake


class TestResolveMessageId:
    def test_prefers_explicit_id(self):
        assert agent._resolve_message_id("abc-123", "ignored", "inbox") == "abc-123"

    def test_locates_first_match_by_query(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(make_message()))
        assert agent._resolve_message_id("", "exam", "inbox") == "msg-1"

    def test_returns_none_when_no_match(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(None))
        assert agent._resolve_message_id("", "nothing", "inbox") is None

    def test_returns_none_for_empty_input(self):
        assert agent._resolve_message_id("", "", "inbox") is None


class TestTools:
    def test_search_mail_formats_results(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(make_message()))
        text = agent.search_mail.invoke({"query": "exam"})
        assert "msg-1" in text
        assert "Exam Reminder" in text
        assert "prof@nus.edu.sg" in text

    def test_search_mail_empty_result(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(None))
        assert "No matching messages found" in agent.search_mail.invoke({"query": "zzz"})

    def test_search_mail_passes_date_range(self, monkeypatch):
        captured: dict = {}

        def fake_list_messages(
            folder,
            q="",
            unread=None,
            starred=None,
            after=None,
            before=None,
            page=0,
            size=10,
        ):
            captured.update(
                folder=folder, q=q, unread=unread, starred=starred,
                after=after, before=before, page=page, size=size,
            )
            return [make_message()], 1, False

        monkeypatch.setattr(agent.gmail_service, "list_messages", fake_list_messages)
        text = agent.search_mail.invoke({
            "query": "exam",
            "after": "2026-08-01",
            "before": "2026-08-31",
        })
        assert "Found 1 message(s)" in text
        assert captured["after"] == "2026-08-01"
        assert captured["before"] == "2026-08-31"
        assert captured["q"] == "exam"

    def test_read_mail_returns_full_body(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(make_message()))
        monkeypatch.setattr(agent.gmail_service, "get_message", lambda message_id: make_message(id=message_id))
        text = agent.read_mail.invoke({"query": "exam"})
        assert "Exam Reminder" in text
        assert "starts at 9am" in text

    def test_read_mail_shows_local_time(self, monkeypatch):
        message = make_message(
            created_at=datetime(2026, 8, 14, 23, 39, tzinfo=timezone.utc)
        )
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(message))
        monkeypatch.setattr(agent.gmail_service, "get_message", lambda message_id: message)
        text = agent.read_mail.invoke({"query": "exam"})
        # Must render in local time (astimezone), not raw UTC, so the assistant
        # agrees with the browser-formatted web UI.
        expected = message.created_at.astimezone().strftime("%Y-%m-%d %H:%M")
        assert f"Date: {expected}" in text
        assert "Date: 2026-08-14 23:39" not in text

    def test_fmt_message_has_local_time(self):
        message = make_message(
            created_at=datetime(2026, 8, 14, 23, 39, tzinfo=timezone.utc)
        )
        text = agent._fmt_message(message)
        # Local date+time, not a bare UTC date and not UTC's time-of-day.
        expected = message.created_at.astimezone().strftime("%Y-%m-%d %H:%M")
        assert expected in text

    def test_delete_mail_calls_trash(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(make_message()))
        monkeypatch.setattr(agent.gmail_service, "trash_message", lambda message_id: make_message(id=message_id))
        text = agent.delete_mail.invoke({"query": "exam"})
        assert "Deleted email 'Exam Reminder'" in text

    def test_delete_mail_batch_trashes_many(self, monkeypatch):
        monkeypatch.setattr(
            agent.gmail_service,
            "list_messages",
            lambda *args, **kwargs: (
                [make_message(id="a"), make_message(id="b")], 2, False,
            ),
        )
        monkeypatch.setattr(agent.gmail_service, "trash_messages", lambda ids: len(ids))
        text = agent.delete_mail_batch.invoke({"folder": "spam", "before": "2026-08-10"})
        assert "Moved 2 email(s) to trash" in text

    def test_delete_mail_batch_no_matches(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(None))
        text = agent.delete_mail_batch.invoke({"folder": "spam"})
        assert "No matching emails found" in text

    def test_delete_mail_is_idempotent_within_window(self, monkeypatch):
        calls: list[str] = []
        monkeypatch.setattr(
            agent.gmail_service,
            "list_messages",
            _list_returning(make_message(id="msg-1")),
        )

        def fake_trash(message_id):
            calls.append(message_id)
            return make_message(id=message_id)

        monkeypatch.setattr(agent.gmail_service, "trash_message", fake_trash)
        monkeypatch.setattr(agent, "_is_recently_trashed", lambda mid: mid in calls)

        first = agent.delete_mail.invoke({"message_id": "msg-1"})
        second = agent.delete_mail.invoke({"message_id": "msg-1"})
        assert "Deleted email" in first
        assert "already deleted" in second
        assert calls == ["msg-1"]  # trash_message called only once

    def test_delete_mail_batch_skips_recently_trashed(self, monkeypatch):
        monkeypatch.setattr(
            agent.gmail_service,
            "list_messages",
            lambda *args, **kwargs: (
                [make_message(id="a"), make_message(id="b")], 2, False,
            ),
        )
        trashed: list[str] = []
        monkeypatch.setattr(agent.gmail_service, "trash_messages", lambda ids: trashed.extend(ids) or len(ids))
        monkeypatch.setattr(agent, "_is_recently_trashed", lambda mid: mid == "a")

        text = agent.delete_mail_batch.invoke({"folder": "spam"})
        assert "Moved 1 email(s) to trash" in text
        assert "1 already deleted skipped" in text
        assert trashed == ["b"]

    def test_star_mail_calls_update(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(make_message()))
        monkeypatch.setattr(
            agent.gmail_service,
            "update_message",
            lambda message_id, starred: make_message(id=message_id, starred=starred),
        )
        text = agent.star_mail.invoke({"query": "exam", "starred": True})
        assert "starred" in text
        unstarred = agent.star_mail.invoke({"query": "exam", "starred": False})
        assert "unstarred" in unstarred

    def test_archive_mail_calls_archive(self, monkeypatch):
        monkeypatch.setattr(agent.gmail_service, "list_messages", _list_returning(make_message()))
        monkeypatch.setattr(agent.gmail_service, "archive_message", lambda message_id: make_message(id=message_id))
        text = agent.archive_mail.invoke({"query": "exam"})
        assert "Archived email 'Exam Reminder'" in text

    def test_send_mail_validates_and_sends(self, monkeypatch):
        def fake_send(request):
            return make_message(
                id="sent-1",
                folder=MailFolder.sent,
                read=True,
                recipients=request.recipients,
            )

        monkeypatch.setattr(agent.gmail_service, "send_message", fake_send)
        text = agent.send_mail.invoke({
            "recipients": ["ta@nus.edu.sg"],
            "subject": "Question",
            "body": "Could you clarify?",
        })
        assert "Sent email 'Exam Reminder'" in text
        assert "ta@nus.edu.sg" in text

    def test_send_mail_rejects_bad_recipients(self):
        text = agent.send_mail.invoke({
            "recipients": ["not-an-email"],
            "subject": "Question",
            "body": "Hello",
        })
        assert "Invalid email request" in text


class TestBuildAgent:
    def test_prompt_embeds_current_date(self, monkeypatch):
        built: list[Any] = []

        def fake_create_react_agent(llm, tools, prompt, checkpointer):
            built.append(prompt)
            return object()

        monkeypatch.setattr(agent, "is_configured", lambda: True)
        monkeypatch.setattr(agent, "_llm", lambda: object())
        monkeypatch.setattr(agent, "create_react_agent", fake_create_react_agent)
        agent._agent_cache.clear()
        try:
            agent.build_agent()
        finally:
            agent._agent_cache.clear()
        assert len(built) == 1
        content = built[0].content
        assert "Current date" in content
        assert "20" in content  # any real year is embedded

    def test_cache_is_keyed_by_date(self, monkeypatch):
        built: list[str] = []

        def fake_create_react_agent(llm, tools, prompt, checkpointer):
            built.append(prompt.content)
            return object()

        monkeypatch.setattr(agent, "is_configured", lambda: True)
        monkeypatch.setattr(agent, "_llm", lambda: object())
        monkeypatch.setattr(agent, "create_react_agent", fake_create_react_agent)
        agent._agent_cache.clear()
        try:
            agent.build_agent()
            agent.build_agent()  # same date -> cached, not rebuilt
            assert len(built) == 1
        finally:
            agent._agent_cache.clear()


class TestRunChat:
    def test_structured_result_with_actions(self, monkeypatch):
        class FakeAgent:
            async def ainvoke(self, payload, config=None):
                return {
                    "messages": [
                        HumanMessage(content="hi"),
                        AIMessage(
                            content="Here are your messages.",
                            tool_calls=[{"name": "search_mail", "args": {"query": "exam"}, "id": "call-1"}],
                        ),
                    ]
                }

        monkeypatch.setattr(agent, "build_agent", lambda: FakeAgent())
        result = asyncio.run(agent.run_chat("hi", "session-1"))
        assert result["response"] == "Here are your messages."
        assert result["session_id"] == "session-1"
        assert result["actions_taken"] == [{"tool": "search_mail", "args": {"query": "exam"}}]

    def test_requires_configuration(self, monkeypatch):
        monkeypatch.setattr(agent, "is_configured", lambda: False)
        with pytest.raises(RuntimeError, match="not configured"):
            asyncio.run(agent.run_chat("hi", "session-1"))


class TestChatEndpoint:
    def test_chat_returns_agent_response(self, monkeypatch):
        monkeypatch.setattr(agent, "is_configured", lambda: True)

        async def fake_run_chat(message: str, session_id: str):
            return {
                "response": "Found 1 email.",
                "session_id": session_id,
                "actions_taken": [{"tool": "search_mail", "args": {"query": message}}],
                "model": "test-model",
            }

        monkeypatch.setattr(agent, "run_chat", fake_run_chat)
        client = TestClient(app)
        response = client.post(
            "/api/mail/agent/chat",
            headers={"Authorization": "Bearer test-user"},
            json={"message": "find exam email", "session_id": "sess-1"},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["response"] == "Found 1 email."
        assert body["session_id"] == "sess-1"
        assert body["actions_taken"][0]["tool"] == "search_mail"
        assert body["model"] == "test-model"

    def test_chat_503_when_not_configured(self, monkeypatch):
        monkeypatch.setattr(agent, "is_configured", lambda: False)
        client = TestClient(app)
        response = client.post(
            "/api/mail/agent/chat",
            headers={"Authorization": "Bearer test-user"},
            json={"message": "hello"},
        )
        assert response.status_code == 503
        assert response.json()["code"] == "MAIL_AGENT_NOT_CONFIGURED"

    def test_chat_requires_auth_header(self):
        client = TestClient(app)
        response = client.post("/api/mail/agent/chat", json={"message": "hello"})
        assert response.status_code == 401
