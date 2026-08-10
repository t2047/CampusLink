"""Map Spring Facilities MCP envelopes to Domain Agent responses."""

from typing import Any

from .models import ActionTaken, InvokeResponse
from .planner import PlannerError
from .tool_client import ToolClientError

BUSINESS_ERROR_CODES = frozenset(
    {
        "BOOKING_CONFLICT",
        "BOOKING_NOT_FOUND",
        "BOOKING_CANCELLATION_NOT_ALLOWED",
        "SPACE_NOT_FOUND",
        "SPACE_UNAVAILABLE",
        "INVALID_TIME",
        "INVALID_LOCATION",
        "INVALID_MAINTENANCE_REQUEST",
        "TICKET_NOT_FOUND",
        "VALIDATION_ERROR",
        "INVALID_SPACE_TYPE",
        "INVALID_CAPACITY",
        "INVALID_PRIORITY",
        "INVALID_MAINTENANCE_STATUS",
        "INVALID_MAINTENANCE_TRANSITION",
        "INVALID_STATUS_TRANSITION",
        "CONFLICT",
        "NOT_FOUND",
        "BUSINESS_ERROR",
    }
)

NEEDS_MORE_INFO_CODES = frozenset(
    {
        "INVALID_TIME",
        "INVALID_LOCATION",
        "INVALID_MAINTENANCE_REQUEST",
        "VALIDATION_ERROR",
    }
)

BUSINESS_MESSAGES = {
    "BOOKING_CONFLICT": (
        "That space was booked before the request completed. No booking was created. "
        "Please choose another space or time."
    ),
    "BOOKING_NOT_FOUND": "I could not find that booking for your account.",
    "BOOKING_CANCELLATION_NOT_ALLOWED": "That booking can no longer be cancelled.",
    "SPACE_NOT_FOUND": "I could not find that campus space. Please check the location.",
    "SPACE_UNAVAILABLE": "That space is not available for the requested time.",
    "INVALID_TIME": "Please provide a valid future start and end time.",
    "INVALID_LOCATION": "Please provide the building and room number.",
    "INVALID_MAINTENANCE_REQUEST": "Please provide the facility and problem details.",
    "TICKET_NOT_FOUND": "I could not find that maintenance request for your account.",
    "VALIDATION_ERROR": "Some required information is missing or invalid.",
    "INVALID_SPACE_TYPE": "Please choose a supported campus space type.",
    "INVALID_CAPACITY": "Please provide a valid minimum capacity.",
    "INVALID_PRIORITY": "Maintenance priority must be LOW, MEDIUM, or HIGH.",
    "INVALID_MAINTENANCE_STATUS": "That maintenance status is invalid.",
    "INVALID_MAINTENANCE_TRANSITION": "That maintenance status change is not allowed.",
    "INVALID_STATUS_TRANSITION": "That status change is not allowed.",
    "CONFLICT": "The facilities request conflicts with the current resource state.",
    "NOT_FOUND": "I could not find that resource for your account.",
    "BUSINESS_ERROR": "The facilities request could not be completed.",
}


def _error_parts(envelope: dict[str, Any]):
    error = envelope.get("error") or {}
    return error.get("code") or "FACILITIES_BUSINESS_ERROR", error.get("message")


def map_tool_result(
    tool_name: str,
    envelope: dict[str, Any],
    shared_context: dict[str, Any],
    request_id: str,
    params_summary: str | None = None,
    success_message: str | None = None,
) -> InvokeResponse:
    if envelope.get("success") is True:
        return InvokeResponse(
            response=success_message
            or "The facilities request completed successfully.",
            status="completed",
            shared_context=shared_context,
            actions_taken=[
                ActionTaken(
                    action=tool_name,
                    params_summary=params_summary,
                    result_summary="Facilities tool completed",
                    status="success",
                )
            ],
            request_id=request_id,
            error=None,
        )

    code, backend_message = _error_parts(envelope)
    if code in {"AUTHENTICATION_REQUIRED", "UNAUTHORIZED", "FORBIDDEN"}:
        return InvokeResponse(
            response="The facilities authentication context is invalid. Please sign in again.",
            status="failed",
            shared_context=shared_context,
            actions_taken=[
                ActionTaken(
                    action=tool_name,
                    params_summary=params_summary,
                    result_summary=backend_message or "Authentication failed",
                    error_code=code,
                    status="failed",
                )
            ],
            request_id=request_id,
            error="FACILITIES_AUTHENTICATION_FAILED",
        )
    if code == "INTERNAL_ERROR" or code not in BUSINESS_ERROR_CODES:
        return InvokeResponse(
            response="The facilities service encountered an internal error. Please try again later.",
            status="failed",
            shared_context=shared_context,
            actions_taken=[
                ActionTaken(
                    action=tool_name,
                    params_summary=params_summary,
                    result_summary="Facilities backend failed",
                    error_code=code,
                    status="failed",
                )
            ],
            request_id=request_id,
            error="FACILITIES_BACKEND_ERROR",
        )
    status = "needs_more_info" if code in NEEDS_MORE_INFO_CODES else "completed"
    message = BUSINESS_MESSAGES.get(
        code, backend_message or "The facilities request could not be completed."
    )
    return InvokeResponse(
        response=message,
        status=status,
        shared_context=shared_context,
        actions_taken=[
            ActionTaken(
                action=tool_name,
                params_summary=params_summary,
                result_summary=backend_message or "No change was made",
                error_code=code,
                status="failed",
            )
        ],
        request_id=request_id,
        error=None,
    )


def map_technical_error(
    error: Exception,
    shared_context: dict[str, Any],
    request_id: str,
) -> InvokeResponse:
    code = (
        error.code
        if isinstance(error, (ToolClientError, PlannerError))
        else "FACILITIES_ADAPTER_ERROR"
    )
    if code == "FACILITIES_PLANNER_NOT_CONFIGURED":
        message = "The Facilities natural-language planner is not configured."
    elif isinstance(error, PlannerError):
        message = "The Facilities natural-language planner is temporarily unavailable."
    else:
        message = "The facilities service is temporarily unavailable. Please try again later."
    return InvokeResponse(
        response=message,
        status="failed",
        shared_context=shared_context,
        actions_taken=[],
        request_id=request_id,
        error=(f"{code}: {error}" if isinstance(error, PlannerError) else code),
    )
