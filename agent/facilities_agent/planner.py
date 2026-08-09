"""Planner abstraction for deterministic Adapter Core orchestration."""

from abc import ABC, abstractmethod
from copy import deepcopy
from typing import Any, Dict, List, Mapping, Union

from pydantic import BaseModel, ConfigDict, Field

from .models import FacilitiesSharedContext, InvokeRequest


class PlannerDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    intent: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class FacilitiesPlanner(ABC):
    @abstractmethod
    async def plan(
        self,
        request: InvokeRequest,
        context: FacilitiesSharedContext,
    ) -> PlannerDecision:
        """Return a validated intent and extracted parameters.

        Authenticated identity is intentionally absent from this interface.
        """


class FakePlanner(FacilitiesPlanner):
    """Fixture-backed planner for unit tests and local demonstrations."""

    def __init__(
        self,
        decisions: Mapping[str, Union[PlannerDecision, List[PlannerDecision]]],
    ) -> None:
        self._decisions = {}
        for message, configured in decisions.items():
            values = configured if isinstance(configured, list) else [configured]
            self._decisions[message] = [
                decision.model_copy(deep=True) for decision in values
            ]
        self.calls: List[Dict[str, Any]] = []

    async def plan(
        self,
        request: InvokeRequest,
        context: FacilitiesSharedContext,
    ) -> PlannerDecision:
        self.calls.append(
            {
                "message": request.message,
                "context": context.model_dump(by_alias=True, mode="json"),
            }
        )
        configured = self._decisions.get(request.message)
        if not configured:
            raise ValueError("No fake planner decision configured for this message")
        if len(configured) > 1:
            decision = configured.pop(0)
        else:
            decision = configured[0]
        return PlannerDecision.model_validate(deepcopy(decision.model_dump()))
