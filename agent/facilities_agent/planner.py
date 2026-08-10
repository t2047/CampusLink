"""Strict planner contracts for Facilities intent extraction."""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping
from copy import deepcopy
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .models import FacilitiesSharedContext, InvokeRequest

FacilityIntent = Literal[
    "search_spaces",
    "get_space_details",
    "check_availability",
    "create_booking",
    "list_user_bookings",
    "get_booking_status",
    "cancel_booking",
    "submit_maintenance_request",
    "get_maintenance_status",
    "list_user_maintenance_requests",
    "unsupported",
]

FACILITIES_INTENTS = frozenset(
    {
        "search_spaces",
        "get_space_details",
        "check_availability",
        "create_booking",
        "list_user_bookings",
        "get_booking_status",
        "cancel_booking",
        "submit_maintenance_request",
        "get_maintenance_status",
        "list_user_maintenance_requests",
        "unsupported",
    }
)

SENSITIVE_IDENTITY_FIELDS = frozenset(
    {"userId", "user_id", "email", "role", "ownerId", "owner_id"}
)

_ALIASES = {
    "space_id": "spaceId",
    "booking_id": "bookingId",
    "ticket_id": "ticketId",
    "candidate_rank": "candidateRank",
    "space_type": "spaceType",
    "minimum_capacity": "minimumCapacity",
    "start_date_time": "startDateTime",
    "end_date_time": "endDateTime",
    "check_availability": "checkAvailability",
    "room_number": "roomNumber",
    "facility_type": "facilityType",
}

_ARGUMENT_FIELDS_BY_INTENT = {
    "search_spaces": frozenset(
        {
            "query",
            "building",
            "spaceType",
            "minimumCapacity",
            "equipment",
            "startDateTime",
            "endDateTime",
        }
    ),
    "get_space_details": frozenset({"spaceId", "candidateRank", "reference"}),
    "check_availability": frozenset(
        {
            "spaceId",
            "candidateRank",
            "reference",
            "startDateTime",
            "endDateTime",
        }
    ),
    "create_booking": frozenset(
        {
            "spaceId",
            "candidateRank",
            "reference",
            "startDateTime",
            "endDateTime",
            "checkAvailability",
        }
    ),
    "list_user_bookings": frozenset(),
    "get_booking_status": frozenset({"bookingId", "candidateRank", "reference"}),
    "cancel_booking": frozenset({"bookingId", "candidateRank", "reference"}),
    "submit_maintenance_request": frozenset(
        {
            "spaceId",
            "candidateRank",
            "reference",
            "building",
            "roomNumber",
            "facilityType",
            "description",
            "priority",
        }
    ),
    "get_maintenance_status": frozenset({"ticketId", "reference"}),
    "list_user_maintenance_requests": frozenset(),
    "unsupported": frozenset(),
}

_POSITIVE_INTEGER_FIELDS = frozenset(
    {"spaceId", "bookingId", "ticketId", "candidateRank", "minimumCapacity"}
)
_STRING_LIMITS = {
    "query": 500,
    "building": 200,
    "reference": 200,
    "roomNumber": 100,
    "facilityType": 200,
    "description": 2000,
    "startDateTime": 40,
    "endDateTime": 40,
}
_SPACE_TYPES = frozenset(
    {"STUDY_ROOM", "SEMINAR_ROOM", "SPORTS_VENUE", "LAB", "LECTURE_ROOM", "ANY"}
)
_PRIORITIES = frozenset({"LOW", "MEDIUM", "HIGH"})
_MISSING_FIELDS = frozenset(
    set().union(*_ARGUMENT_FIELDS_BY_INTENT.values())
    | {"datetime", "date", "startDateTime", "endDateTime", "location", "intent"}
)


class PlannerError(RuntimeError):
    """Safe planner failure exposed through the Adapter error taxonomy."""

    code = "FACILITIES_PLANNER_ERROR"


class PlannerConfigurationError(PlannerError):
    code = "FACILITIES_PLANNER_NOT_CONFIGURED"


class PlannerUnavailableError(PlannerError):
    code = "FACILITIES_PLANNER_UNAVAILABLE"


class PlannerTimeoutError(PlannerUnavailableError):
    code = "FACILITIES_PLANNER_TIMEOUT"


class PlannerOutputError(PlannerError):
    code = "FACILITIES_PLANNER_INVALID_OUTPUT"


class PlannerDecision(BaseModel):
    """Validated semantic decision; it never contains authenticated identity."""

    model_config = ConfigDict(extra="forbid", strict=True)

    intent: FacilityIntent
    arguments: dict[str, Any] = Field(default_factory=dict)
    datetime_text: str | None = Field(default=None, max_length=300)
    missing_fields: list[str] = Field(default_factory=list, max_length=10)
    clarification: str | None = Field(default=None, max_length=500)

    @field_validator("arguments", mode="before")
    @classmethod
    def validate_arguments(cls, value: Any) -> dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError("planner arguments must be an object")
        if SENSITIVE_IDENTITY_FIELDS.intersection(value):
            raise ValueError("planner output must not contain identity fields")

        normalized: dict[str, Any] = {}
        for raw_key, raw_value in value.items():
            if not isinstance(raw_key, str):
                raise TypeError("planner argument names must be strings")
            key = _ALIASES.get(raw_key, raw_key)
            if key in normalized:
                raise ValueError("planner output contains duplicate argument aliases")
            normalized[key] = cls._sanitize_argument(key, raw_value)
        return normalized

    @field_validator("datetime_text", "clarification")
    @classmethod
    def strip_optional_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = value.strip()
        return stripped or None

    @field_validator("missing_fields")
    @classmethod
    def validate_missing_fields(cls, value: list[str]) -> list[str]:
        result: list[str] = []
        for field in value:
            if not isinstance(field, str) or not field.strip():
                raise ValueError("missing field names must be non-empty strings")
            normalized = _ALIASES.get(field.strip(), field.strip())
            if normalized in SENSITIVE_IDENTITY_FIELDS:
                raise ValueError("identity cannot be requested as a missing field")
            if normalized not in _MISSING_FIELDS:
                raise ValueError(f"unknown missing field: {normalized}")
            if normalized not in result:
                result.append(normalized)
        return result

    @model_validator(mode="after")
    def validate_intent_arguments(self) -> PlannerDecision:
        allowed = _ARGUMENT_FIELDS_BY_INTENT[self.intent]
        unexpected = set(self.arguments) - allowed
        if unexpected:
            raise ValueError(
                "arguments are not allowed for intent: {}".format(
                    ", ".join(sorted(unexpected))
                )
            )
        return self

    @classmethod
    def _sanitize_argument(cls, key: str, value: Any) -> Any:
        known = set().union(*_ARGUMENT_FIELDS_BY_INTENT.values())
        if key not in known:
            raise ValueError(f"unknown planner argument: {key}")
        if key in _POSITIVE_INTEGER_FIELDS:
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError(f"{key} must be a positive integer")
            return value
        if key == "equipment":
            if not isinstance(value, list) or len(value) > 20:
                raise ValueError("equipment must be a bounded string array")
            equipment = []
            for item in value:
                if not isinstance(item, str) or not item.strip() or len(item.strip()) > 100:
                    raise ValueError("equipment values must be non-empty strings")
                equipment.append(item.strip())
            return equipment
        if key == "checkAvailability":
            if not isinstance(value, bool):
                raise ValueError("checkAvailability must be a boolean")
            return value
        if key == "spaceType":
            if not isinstance(value, str) or value.strip().upper() not in _SPACE_TYPES:
                raise ValueError("unsupported space type")
            return value.strip().upper()
        if key == "priority":
            if not isinstance(value, str) or value.strip().upper() not in _PRIORITIES:
                raise ValueError("unsupported maintenance priority")
            return value.strip().upper()
        limit = _STRING_LIMITS.get(key)
        if limit is not None:
            if not isinstance(value, str) or not value.strip() or len(value.strip()) > limit:
                raise ValueError(f"{key} must be a bounded non-empty string")
            return value.strip()
        raise ValueError(f"unsupported planner argument: {key}")


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
    """Fixture-backed planner for tests; production must not fall back to it."""

    def __init__(
        self,
        decisions: Mapping[str, PlannerDecision | list[PlannerDecision]],
    ) -> None:
        self._decisions = {}
        for message, configured in decisions.items():
            values = configured if isinstance(configured, list) else [configured]
            self._decisions[message] = [
                decision.model_copy(deep=True) for decision in values
            ]
        self.calls: list[dict[str, Any]] = []

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
        decision = configured.pop(0) if len(configured) > 1 else configured[0]
        return PlannerDecision.model_validate(deepcopy(decision.model_dump()))
