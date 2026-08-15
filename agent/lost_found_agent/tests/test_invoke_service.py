"""invoke_service 集成测试：REST/MCP 共用主流程 + 记忆落库（§7.1）。

用 FakeCampusApiClient + FakeMemoryClient 驱动多轮报失流程，断言：
消息入 lf 会话、pending 草稿随会话持久化、确认成功后用户级事实落库、
记忆降级不阻断主流程。
"""

import pytest

from lost_found_agent.confirmation import ConfirmationStore
from lost_found_agent.invoke_service import LostFoundInvokeService
from lost_found_agent.memory import MemoryClient, MemoryManager
from lost_found_agent.models import ConversationContext, InvokeRequest, TraceParent, VerifiedRequest
from lost_found_agent.rules import RuleEngine


def _verified(user_id: str = "42") -> VerifiedRequest:
    return VerifiedRequest(
        user_id=user_id, user_role="STUDENT", intended_action="invoke", nonce=""
    )


def _payload(message: str, shared_data=None, *, confirmed=False, confirmation_id=None) -> InvokeRequest:
    return InvokeRequest(
        message=message,
        conversation_context=ConversationContext(
            session_id="s1", shared_data=shared_data or {}
        ),
        confirmed=confirmed,
        confirmation_id=confirmation_id,
        trace_parent=TraceParent(trace_id="svc-test"),
    )


def _service(settings, fake_api, fake_memory, confirmations=None) -> LostFoundInvokeService:
    store = confirmations or ConfirmationStore(ttl_seconds=600)
    rule_engine = RuleEngine(fake_api, store, 0.5)
    memory = MemoryManager(fake_memory, store)
    return LostFoundInvokeService(
        settings=settings,
        rule_engine=rule_engine,
        memory_manager=memory,
        llm_interpreter=None,
    )


async def test_multi_turn_report_lost_flow(settings, fake_api, fake_memory) -> None:
    """多轮报失：追问 → 补齐 → 确认创建；消息/草稿/事实全部落库。"""
    service = _service(settings, fake_api, fake_memory)

    # Turn 1：只说了物品与颜色 → 追问缺失字段
    r1 = await service.handle_invoke(
        _payload("我丢了把红色雨伞"), _verified(), "t1", include_recent_messages=True
    )
    assert r1.status == "needs_more_info"

    # Turn 2：补齐类别/地点/日期/描述 → 生成确认
    r2 = await service.handle_invoke(
        _payload(
            "类别：雨伞，地点：图书馆，日期：2026-08-10，描述：这是一把红色的雨伞。",
            r1.shared_context,
        ),
        _verified(),
        "t2",
        include_recent_messages=True,
    )
    assert r2.status == "needs_confirmation"
    assert r2.confirmation_required is not None
    confirmation_id = r2.confirmation_required.confirmation_id

    # 确认草稿已随会话持久化
    session = await fake_memory.get_session(_verified(), "s1")
    assert session["pending_confirmation"]["confirmation_id"] == confirmation_id

    # Turn 3：确认 → 创建报失，事实落库
    r3 = await service.handle_invoke(
        _payload("确认", r2.shared_context, confirmed=True, confirmation_id=confirmation_id),
        _verified(),
        "t3",
        include_recent_messages=True,
    )
    assert r3.status in ("completed", "match_found")
    assert any(call[0] == "report_lost" and call[1] == "42" for call in fake_api.calls)

    # 事实（LOST_ITEM）落库
    assert any(
        fact["fact_type"] == "LOST_ITEM" and fact["category"] == "UMBRELLA"
        for fact in fake_memory.facts
    )
    # 确认后草稿清除；每轮 USER+AGENT 两条消息（3 轮 = 6 条）
    session_after = await fake_memory.get_session(_verified(), "s1")
    assert "pending_confirmation" not in session_after
    assert len(session_after["messages"]) == 6


async def test_panel_and_mcp_injection_flags(settings, fake_api, fake_memory) -> None:
    """面板链路注入 recent_messages；MCP 链路不注入（§7.6）。"""
    verified = _verified()
    await fake_memory.upsert_session(verified, "s1")
    await fake_memory.append_message(verified, "s1", "USER", "older turn text")
    service = _service(settings, fake_api, fake_memory)

    panel_ctx = await service._memory.build_context(
        _verified(), "s1", include_recent_messages=True
    )
    assert len(panel_ctx["recent_messages"]) == 1

    mcp_ctx = await service._memory.build_context(
        _verified(), "s1", include_recent_messages=False
    )
    assert mcp_ctx["recent_messages"] == []


async def test_memory_degraded_does_not_block(settings, fake_api) -> None:
    """记忆后端不可用 → 降级无记忆执行，invoke 不失败（§4 设计原则）。"""
    store = ConfirmationStore(ttl_seconds=600)
    rule_engine = RuleEngine(fake_api, store, 0.5)
    # MemoryClient 包一层会 AttributeError 的 fake api：第一次调用即进入降级窗口
    memory = MemoryManager(MemoryClient(fake_api), store)
    service = LostFoundInvokeService(
        settings=settings, rule_engine=rule_engine, memory_manager=memory, llm_interpreter=None
    )
    response = await service.handle_invoke(
        _payload("我丢了把伞"), _verified(), "t-degraded", include_recent_messages=True
    )
    assert response.status == "needs_more_info"
    assert "内部错误" not in response.response


async def test_fail_closed_returns_failed(settings, fake_api, fake_memory) -> None:
    """llm_fail_closed=true + LLM 不可用 → 显式 failed，不降级规则。"""
    from lost_found_agent.llm import LlmUnavailable

    class _Down:
        async def interpret(self, message, shared_context, memory_context=None):
            raise LlmUnavailable("down")

    settings = settings.model_copy(update={"llm_fail_closed": True})
    store = ConfirmationStore(ttl_seconds=600)
    rule_engine = RuleEngine(fake_api, store, 0.5)
    memory = MemoryManager(fake_memory, store)
    service = LostFoundInvokeService(
        settings=settings, rule_engine=rule_engine, memory_manager=memory, llm_interpreter=_Down()
    )
    response = await service.handle_invoke(
        _payload("我丢了把伞"), _verified(), "t-fc", include_recent_messages=True
    )
    assert response.status == "failed"
    assert "暂时不可用" in response.response
