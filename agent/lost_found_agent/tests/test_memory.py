"""记忆模块单测：FactExtractor / MemoryManager / 摘要滚动 / pending 同步（§7.3-§7.5）。"""

import time

import pytest

from lost_found_agent.confirmation import ConfirmationStore
from lost_found_agent.memory import FactExtractor, MemoryManager
from lost_found_agent.models import (
    ConfirmationRequired,
    ConversationContext,
    InvokeRequest,
    InvokeResponse,
    TraceParent,
    VerifiedRequest,
)


def _verified(user_id: str = "42") -> VerifiedRequest:
    return VerifiedRequest(
        user_id=user_id, user_role="STUDENT", intended_action="invoke", nonce=""
    )


def _payload(message: str, session_id: str = "s1", *, confirmed=False, confirmation_id=None) -> InvokeRequest:
    return InvokeRequest(
        message=message,
        conversation_context=ConversationContext(session_id=session_id, shared_data={}),
        confirmed=confirmed,
        confirmation_id=confirmation_id,
        trace_parent=TraceParent(trace_id="mem-test"),
    )


def _confirmation_response(confirmation_id: str, action: str = "report_lost") -> InvokeResponse:
    return InvokeResponse(
        response=f"请确认{action}信息",
        status="needs_confirmation",
        confirmation_required=ConfirmationRequired(
            confirmation_id=confirmation_id,
            action=action,
            summary="red umbrella, UMBRELLA, Library",
            expires_at="2026-08-15T00:00:00+00:00",
        ),
        request_id="mem-test",
    )


def _manager(fake_memory, *, llm_interpreter=None, confirmations=None) -> MemoryManager:
    return MemoryManager(
        fake_memory,
        confirmations or ConfirmationStore(ttl_seconds=600),
        llm_interpreter=llm_interpreter,
    )


# ─────────────────────────── FactExtractor ───────────────────────────

def test_item_fact_report_lost() -> None:
    fact = FactExtractor.item_fact(
        "report_lost",
        {
            "item_name": "red umbrella",
            "category": "UMBRELLA",
            "colour": "Red",
            "location": "Library",
            "event_date": "2026-08-10",
            "time_description": "afternoon",
        },
    )
    assert fact == {
        "fact_type": "LOST_ITEM",
        "item_name": "red umbrella",
        "category": "UMBRELLA",
        "colour": "Red",
        "location": "Library",
        "event_date": "2026-08-10",
        "time_description": "afternoon",
        "status": "OPEN",
        "confidence": 1.0,
    }


def test_item_fact_report_found_and_rejects() -> None:
    assert FactExtractor.item_fact("report_found", {"item_name": "keys", "category": "KEYS"})[
        "fact_type"
    ] == "FOUND_ITEM"
    assert FactExtractor.item_fact("claim_item", {"item_name": "keys"}) is None
    assert FactExtractor.item_fact("report_lost", {"category": "KEYS"}) is None


# ─────────────────────────── build_context ───────────────────────────

async def test_build_context_empty_session(fake_memory) -> None:
    manager = _manager(fake_memory)
    ctx = await manager.build_context(_verified(), "s-missing")
    assert ctx["session_summary"] == ""
    assert ctx["recent_messages"] == []
    assert ctx["user_facts"] == []
    assert ctx["pending_confirmation"] is None


async def test_build_context_loads_session_and_facts(fake_memory) -> None:
    await fake_memory.upsert_session(_verified(), "s1", summary="earlier turns")
    for text in ("hello", "world", "again"):
        await fake_memory.append_message(_verified(), "s1", "USER", text)
    await fake_memory.upsert_fact(
        _verified(),
        {"fact_type": "LOST_ITEM", "item_name": "umbrella", "category": "UMBRELLA",
         "location": "Library", "status": "OPEN"},
    )
    manager = _manager(fake_memory)
    ctx = await manager.build_context(_verified(), "s1", include_recent_messages=True)
    assert ctx["session_summary"] == "earlier turns"
    assert [m["content"] for m in ctx["recent_messages"]] == ["hello", "world", "again"]
    assert ctx["user_facts"][0]["category"] == "UMBRELLA"


async def test_build_context_mcp_skips_recent_messages(fake_memory) -> None:
    await fake_memory.append_message(_verified(), "s1", "USER", "secret history")
    manager = _manager(fake_memory)
    ctx = await manager.build_context(_verified(), "s1", include_recent_messages=False)
    assert ctx["recent_messages"] == []
    assert ctx["user_facts"] == []


async def test_build_context_restores_pending_into_store(fake_memory) -> None:
    verified = _verified()
    future = time.time() + 300
    await fake_memory.upsert_session(
        verified, "s1",
        pending_confirmation={
            "confirmation_id": "cid-1",
            "action": "report_lost",
            "payload": {"item_name": "umbrella"},
            "created_at": time.time(),
            "expires_at": future,
            "user_id": "42",
            "session_id": "s1",
            "role": "STUDENT",
        },
    )
    store = ConfirmationStore(ttl_seconds=600)
    manager = _manager(fake_memory, confirmations=store)
    ctx = await manager.build_context(verified, "s1")
    assert ctx["pending_confirmation"]["confirmation_id"] == "cid-1"
    restored = store.get("cid-1")
    assert restored is not None
    assert restored.action == "report_lost"
    assert restored.payload == {"item_name": "umbrella"}


# ─────────────────────────── 摘要滚动 ───────────────────────────

async def test_roll_summary_folds_over_threshold(fake_memory) -> None:
    verified = _verified()
    await fake_memory.upsert_session(verified, "s1")
    for i in range(14):
        await fake_memory.append_message(verified, "s1", "USER" if i % 2 == 0 else "AGENT", f"message {i}")
    manager = _manager(fake_memory)
    await manager._roll_summary(verified, "s1")
    session = await fake_memory.get_session(verified, "s1")
    assert len(session["messages"]) == 12
    assert session["summary"] and "message 0" in session["summary"]


async def test_roll_summary_keeps_below_threshold(fake_memory) -> None:
    verified = _verified()
    await fake_memory.upsert_session(verified, "s1")
    for i in range(4):
        await fake_memory.append_message(verified, "s1", "USER", f"m{i}")
    manager = _manager(fake_memory)
    await manager._roll_summary(verified, "s1")
    session = await fake_memory.get_session(verified, "s1")
    assert len(session["messages"]) == 4
    assert not session.get("summary")


# ─────────────────────────── persist_turn ───────────────────────────

async def test_persist_turn_appends_messages(fake_memory) -> None:
    verified = _verified()
    manager = _manager(fake_memory)
    payload = _payload("我丢了把红色雨伞")
    response = InvokeResponse(
        response="还需要更多信息。", status="needs_more_info",
        shared_context={"intent": "report_lost", "item_name": "把红色雨伞", "colour": "红色"},
        request_id="mem-test",
    )
    await manager.persist_turn(
        verified, "s1", "mem-test", payload, None, response, None,
        {"session_summary": "", "recent_messages": [], "user_facts": [], "pending_confirmation": None},
    )
    session = await fake_memory.get_session(verified, "s1")
    roles = [m["role"] for m in session["messages"]]
    assert roles == ["USER", "AGENT"]
    assert session["messages"][0]["message_text"] == "我丢了把红色雨伞"
    assert session["messages"][0]["intent"] == "report_lost"


async def test_persist_turn_writes_pending_on_confirmation(fake_memory) -> None:
    verified = _verified()
    store = ConfirmationStore(ttl_seconds=600)
    manager = _manager(fake_memory, confirmations=store)
    confirmation_id, pending = store.create(
        verified.user_id, "report_lost", {"item_name": "umbrella"}, session_id="s1", role="STUDENT"
    )
    response = _confirmation_response(confirmation_id)
    await manager.persist_turn(
        verified, "s1", "mem-test", _payload("请确认"), None, response, None,
        {"session_summary": "", "recent_messages": [], "user_facts": [], "pending_confirmation": None},
    )
    session = await fake_memory.get_session(verified, "s1")
    pending_json = session["pending_confirmation"]
    assert pending_json["confirmation_id"] == confirmation_id
    assert pending_json["action"] == "report_lost"
    assert pending_json["user_id"] == "42"
    assert pending_json["session_id"] == "s1"


async def test_persist_turn_clears_pending_on_confirm(fake_memory) -> None:
    verified = _verified()
    store = ConfirmationStore(ttl_seconds=600)
    confirmation_id, pending = store.create(
        verified.user_id, "report_lost",
        {"item_name": "umbrella", "category": "UMBRELLA", "location": "Library"},
        session_id="s1", role="STUDENT",
    )
    await fake_memory.upsert_session(
        verified, "s1",
        pending_confirmation={
            "confirmation_id": confirmation_id, "action": "report_lost",
            "payload": {"item_name": "umbrella", "category": "UMBRELLA", "location": "Library"},
            "created_at": pending.created_at,
            "expires_at": pending.expires_at, "user_id": "42", "session_id": "s1", "role": "STUDENT",
        },
    )
    manager = _manager(fake_memory, confirmations=store)
    payload = _payload("确认", confirmed=True, confirmation_id=confirmation_id)
    response = InvokeResponse(response="done", status="completed", request_id="mem-test")
    # 确认轮：pending 先被 consume，persist 用预取的 pre_pending 抽事实并清草稿
    consumed = store.consume(confirmation_id, "42")
    await manager.persist_turn(
        verified, "s1", "mem-test", payload, None, response, consumed,
        {"session_summary": "", "recent_messages": [], "user_facts": [], "pending_confirmation": None},
    )
    session = await fake_memory.get_session(verified, "s1")
    assert "pending_confirmation" not in session
    # 事实从 pre_pending（确认的 payload）抽取
    assert any(f["fact_type"] == "LOST_ITEM" for f in fake_memory.facts)


async def test_persist_turn_clears_expired_pending(fake_memory) -> None:
    verified = _verified()
    past = time.time() - 10
    await fake_memory.upsert_session(
        verified, "s1",
        pending_confirmation={
            "confirmation_id": "cid-old", "action": "report_lost", "payload": {},
            "created_at": past - 600, "expires_at": past, "user_id": "42", "session_id": "s1",
        },
    )
    manager = _manager(fake_memory)
    response = InvokeResponse(response="继续", status="needs_more_info", request_id="mem-test")
    await manager.persist_turn(
        verified, "s1", "mem-test", _payload("继续聊聊"), None, response, None,
        {"session_summary": "", "recent_messages": [], "user_facts": [],
         "pending_confirmation": {"confirmation_id": "cid-old", "expires_at": past}},
    )
    session = await fake_memory.get_session(verified, "s1")
    assert "pending_confirmation" not in session
