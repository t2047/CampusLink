"""Regression tests for checkpointed Chat Core turn isolation."""

from __future__ import annotations

import asyncio
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from orchestration.graph import nodes
from orchestration.graph.nodes import (
    _build_conversation_context,
    agent_invoker,
    response_aggregator,
)
from orchestration.graph.state import AgentState, current_turn_invocations
from orchestration.main import ChatRequest, _build_initial_state
from orchestration.streaming.sse_handler import OrchestrationStreamer


class SequencedAgentClient:
    def __init__(self, results: list[dict[str, Any]]):
        self._results = iter(results)
        self.calls: list[dict[str, Any]] = []

    async def invoke_agent(self, **kwargs):
        self.calls.append(kwargs)
        return next(self._results)


class FailingSummaryLLM:
    def invoke(self, _messages):
        raise AssertionError("a single current-turn result must not be summarized with history")


def _initial_state(message: str, trace_id: str) -> dict[str, Any]:
    state = _build_initial_state(
        ChatRequest(
            userId="42",
            role="STUDENT",
            message=message,
            sessionId="turn-isolation-session",
        ),
        trace_id,
        "turn-isolation-session",
    )
    state["agent_plan"] = ["facility-agent"]
    return state


def _checkpointed_agent_graph(client: SequencedAgentClient):
    builder = StateGraph(AgentState)

    async def invoke(state: AgentState):
        return await agent_invoker(state, client=client)

    builder.add_node("agent_invoker", invoke)
    builder.add_node("response_aggregator", response_aggregator)
    builder.set_entry_point("agent_invoker")
    builder.add_edge("agent_invoker", "response_aggregator")
    builder.add_edge("response_aggregator", END)
    return builder.compile(checkpointer=MemorySaver())


def test_checkpointed_previous_booking_does_not_leak_into_failed_turn(monkeypatch):
    monkeypatch.setattr(nodes, "summary_llm", lambda: FailingSummaryLLM())
    client = SequencedAgentClient(
        [
            {
                "response": "Booking #8 confirmed for 2026-08-16 14:00-16:00.",
                "status": "completed",
                "shared_context": {"facilities": {"last_booking_id": 8}},
                "request_id": "booking-8",
            },
            {
                "response": "The natural-language planner is temporarily unavailable.",
                "status": "failed",
                "shared_context": {"facilities": {"last_booking_id": 8}},
                "request_id": "planner-failed",
                "error": "FACILITIES_PLANNER_INVALID_OUTPUT",
            },
        ]
    )
    graph = _checkpointed_agent_graph(client)
    config = {"configurable": {"thread_id": "turn-isolation-session"}}

    async def scenario():
        first = await graph.ainvoke(_initial_state("Book room 6", "trace-1"), config=config)
        second = await graph.ainvoke(
            _initial_state("I want to book for 8.17 from 10am to 12pm", "trace-2"),
            config=config,
        )
        return first, second

    first, second = asyncio.run(scenario())

    assert first["turn_id"] != second["turn_id"]
    assert len(second["agent_invocations"]) == 2  # history remains available for audit
    assert len(current_turn_invocations(second)) == 1
    final = second["messages"][-1].content
    assert final == "The natural-language planner is temporarily unavailable."
    assert "Booking #8" not in final
    assert "2026-08-16" not in final
    assert "14:00" not in final


def test_response_aggregator_consumes_only_current_turn_invocations():
    state: AgentState = {
        "turn_id": "turn-2",
        "messages": [HumanMessage(content="Book for 8.17 from 10am to 12pm")],
        "agent_invocations": [
            {
                "turn_id": "turn-1",
                "output_status": "completed",
                "output_response": "Booking #8 confirmed for 2026-08-16 14:00-16:00.",
            },
            {
                "turn_id": "turn-2",
                "output_status": "failed",
                "output_response": "The facilities planner is temporarily unavailable.",
            },
        ],
    }

    update = asyncio.run(response_aggregator(state))

    assert update["messages"][-1].content == "The facilities planner is temporarily unavailable."


def test_failed_invocation_does_not_overwrite_last_successful_shared_context():
    context = _build_conversation_context(
        {
            "session_id": "session-1",
            "messages": [HumanMessage(content="try another booking")],
            "conversation_context": {"shared_data": {}},
            "agent_invocations": [
                {
                    "turn_id": "turn-1",
                    "output_status": "completed",
                    "shared_context": {
                        "facilities": {
                            "last_booking_id": 8,
                            "selected_space": {"spaceId": 6},
                        }
                    },
                },
                {
                    "turn_id": "turn-2",
                    "output_status": "failed",
                    "error": "FACILITIES_PLANNER_INVALID_OUTPUT",
                    "shared_context": {
                        "facilities": {
                            "last_booking_id": 999,
                            "selected_space": {"spaceId": 99},
                        }
                    },
                },
            ],
        }
    )

    assert context["shared_data"]["facilities"] == {
        "last_booking_id": 8,
        "selected_space": {"spaceId": 6},
    }


def test_compatibility_streamer_emits_only_current_turn_progress():
    events = OrchestrationStreamer(
        {
            "turn_id": "turn-2",
            "intent_type": "domain_agent",
            "messages": [AIMessage(content="current response")],
            "agent_invocations": [
                {"turn_id": "turn-1", "agent_name": "facility-agent", "output_status": "completed"},
                {"turn_id": "turn-2", "agent_name": "facility-agent", "output_status": "failed"},
            ],
        }
    ).build_events()

    agent_events = [event for event in events if event.event in {"agent_start", "agent_done", "agent_error"}]
    assert [event.event for event in agent_events] == ["agent_start", "agent_error"]
