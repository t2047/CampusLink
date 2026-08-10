"""Pydantic contracts used by the Facilities Adapter Core."""

from datetime import datetime
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class AdapterModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
        serialize_by_alias=True,
    )


class TraceParent(AdapterModel):
    trace_id: Optional[str] = None
    parent_span_id: Optional[str] = None


class ConversationContext(AdapterModel):
    previous_agent: Optional[str] = None
    session_id: Optional[str] = Field(default=None, max_length=128)
    shared_data: Dict[str, Any] = Field(default_factory=dict)


class SpaceCandidate(AdapterModel):
    rank: int = Field(ge=1)
    space_id: int = Field(alias="spaceId")
    name: str
    building: Optional[str] = None
    room_number: Optional[str] = Field(default=None, alias="roomNumber")
    space_type: Optional[str] = Field(default=None, alias="spaceType")
    capacity: Optional[int] = None
    equipment: List[str] = Field(default_factory=list)


class SearchResultsContext(AdapterModel):
    start_date_time: Optional[str] = Field(default=None, alias="startDateTime")
    end_date_time: Optional[str] = Field(default=None, alias="endDateTime")
    expires_at: datetime = Field(alias="expiresAt")
    candidates: List[SpaceCandidate] = Field(default_factory=list, max_length=5)


class BookingCandidate(AdapterModel):
    rank: int = Field(ge=1)
    booking_id: int = Field(alias="bookingId")
    space_id: Optional[int] = Field(default=None, alias="spaceId")
    space_name: Optional[str] = Field(default=None, alias="spaceName")
    start_date_time: Optional[str] = Field(default=None, alias="startDateTime")
    end_date_time: Optional[str] = Field(default=None, alias="endDateTime")
    status: Optional[str] = None


class PendingMaintenanceInfo(AdapterModel):
    space_id: Optional[int] = Field(default=None, alias="spaceId")
    building: Optional[str] = None
    room_number: Optional[str] = Field(default=None, alias="roomNumber")
    facility_type: Optional[str] = Field(default=None, alias="facilityType")
    description: Optional[str] = None
    priority: str = "MEDIUM"
    missing_fields: List[str] = Field(default_factory=list, alias="missingFields")


class FacilitiesSharedContext(AdapterModel):
    version: Literal[1] = 1
    last_intent: Optional[str] = None
    search_results: Optional[SearchResultsContext] = None
    selected_space: Optional[SpaceCandidate] = None
    booking_candidates: List[BookingCandidate] = Field(
        default_factory=list, max_length=10
    )
    last_booking_id: Optional[int] = None
    last_maintenance_ticket_id: Optional[int] = None
    pending_maintenance_info: Optional[PendingMaintenanceInfo] = None
    updated_at: Optional[datetime] = Field(default=None, alias="updatedAt")


class ConfirmationRequired(AdapterModel):
    confirmation_id: str
    action: Literal[
        "create_booking",
        "cancel_booking",
        "submit_maintenance_request",
    ]
    summary: str
    preview: Dict[str, Any] = Field(default_factory=dict)
    expires_at: datetime


class ActionTaken(AdapterModel):
    action: str
    params_summary: Optional[str] = None
    result_summary: Optional[str] = None
    error_code: Optional[str] = None
    status: Literal["success", "failed", "skipped"]


class InvokeRequest(AdapterModel):
    message: str = Field(min_length=1, max_length=4000)
    conversation_context: ConversationContext = Field(
        default_factory=ConversationContext
    )
    confirmed: bool = False
    confirmation_id: Optional[str] = None
    trace_parent: Optional[TraceParent] = None


class InvokeResponse(AdapterModel):
    response: str
    status: Literal[
        "completed",
        "needs_more_info",
        "needs_confirmation",
        "failed",
    ]
    confirmation_required: Optional[ConfirmationRequired] = None
    shared_context: Dict[str, Any] = Field(default_factory=dict)
    actions_taken: List[ActionTaken] = Field(default_factory=list)
    request_id: str
    error: Optional[str] = None
