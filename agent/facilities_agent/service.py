"""Deterministic orchestration core for the Facilities Domain Agent Adapter."""

import secrets
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, Optional

from .confirmation import ConfirmationError, ConfirmationStore, PendingAction
from .context import ContextResolutionError, FacilitiesContextManager
from .datetime_parser import CAMPUS_TIMEZONE, singapore_now
from .models import (
    ActionTaken,
    BookingCandidate,
    ConfirmationRequired,
    InvokeRequest,
    InvokeResponse,
    PendingMaintenanceInfo,
)
from .planner import FacilitiesPlanner, PlannerDecision
from .result_mapper import map_technical_error, map_tool_result
from .tool_client import ToolClient, ToolClientError


class FacilitiesAdapterService:
    """Coordinates planner decisions and tool calls without owning business rules."""

    def __init__(
        self,
        planner: FacilitiesPlanner,
        tool_client: ToolClient,
        confirmation_store: Optional[ConfirmationStore] = None,
        now_provider=singapore_now,
    ) -> None:
        self._planner = planner
        self._tool_client = tool_client
        self._now_provider = now_provider
        self._confirmation_store = confirmation_store or ConfirmationStore(
            now_provider=self._utc_now
        )

    async def invoke(
        self,
        request: InvokeRequest,
        authenticated_user_id: str,
        request_id: Optional[str] = None,
    ) -> InvokeResponse:
        """Invoke using identity supplied by security context, never by request/planner."""
        invocation_id = request_id or "facility-req-{0}".format(
            secrets.token_urlsafe(12)
        )
        context = FacilitiesContextManager.from_shared_data(
            request.conversation_context.shared_data,
            now_provider=self._utc_now,
        )
        session_id = request.conversation_context.session_id or ""

        try:
            if request.confirmed:
                return await self._resume_confirmation(
                    request,
                    str(authenticated_user_id),
                    session_id,
                    context,
                    invocation_id,
                )

            decision = await self._planner.plan(
                request, context.context.model_copy(deep=True)
            )
            return await self._dispatch(
                decision,
                str(authenticated_user_id),
                session_id,
                context,
                invocation_id,
            )
        except ToolClientError as error:
            return map_technical_error(error, context.snapshot(), invocation_id)
        except Exception as error:  # Adapter exceptions are true system failures.
            return map_technical_error(error, context.snapshot(), invocation_id)

    def _utc_now(self):
        current = self._now_provider()
        if current.tzinfo is None:
            current = current.replace(tzinfo=CAMPUS_TIMEZONE)
        return current.astimezone(timezone.utc)

    async def _dispatch(
        self,
        decision: PlannerDecision,
        user_id: str,
        session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        handlers = {
            "search_spaces": self._search_spaces,
            "book_space": self._book_space,
            "create_booking": self._book_space,
            "list_bookings": self._list_bookings,
            "list_user_bookings": self._list_bookings,
            "cancel_booking": self._cancel_booking,
            "submit_maintenance": self._submit_maintenance,
            "submit_maintenance_request": self._submit_maintenance,
            "list_maintenance_requests": self._list_maintenance,
            "list_user_maintenance_requests": self._list_maintenance,
        }
        handler = handlers.get(decision.intent)
        if handler is None:
            return self._needs_more_info(
                "I can help search spaces, manage your bookings, or manage maintenance requests.",
                context,
                request_id,
            )
        return await handler(
            decision.arguments, user_id, session_id, context, request_id
        )

    @staticmethod
    def _value(arguments: Dict[str, Any], camel: str, snake: str = None) -> Any:
        if camel in arguments:
            return arguments.get(camel)
        return arguments.get(snake or camel)

    @classmethod
    def _pick(cls, arguments: Dict[str, Any], allowed: Iterable[str]) -> Dict[str, Any]:
        result = {}
        for key in allowed:
            value = cls._value(arguments, key, cls._camel_to_snake(key))
            if value is not None:
                result[key] = value
        return result

    @staticmethod
    def _camel_to_snake(value: str) -> str:
        output = []
        for char in value:
            if char.isupper():
                output.extend(["_", char.lower()])
            else:
                output.append(char)
        return "".join(output)

    async def _search_spaces(
        self,
        arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        tool_arguments = self._pick(
            arguments,
            (
                "query",
                "building",
                "spaceType",
                "minimumCapacity",
                "equipment",
                "startDateTime",
                "endDateTime",
            ),
        )
        result = await self._tool_client.call_tool("search_spaces", tool_arguments)
        if result.get("success") is not True:
            context.touch("search_spaces")
            return map_tool_result(
                "search_spaces", result, context.snapshot(), request_id
            )

        spaces = result.get("data") or []
        if isinstance(spaces, dict):
            spaces = spaces.get("spaces") or []
        context.replace_search_results(
            spaces,
            tool_arguments.get("startDateTime"),
            tool_arguments.get("endDateTime"),
        )
        count = len(context.context.search_results.candidates)
        if count == 0:
            message = "I could not find a matching campus space."
        else:
            names = [
                "{0}. {1}".format(candidate.rank, candidate.name)
                for candidate in context.context.search_results.candidates
            ]
            message = "I found {0} matching space(s): {1}.".format(
                count, "; ".join(names)
            )
        return InvokeResponse(
            response=message,
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="search_spaces",
                    result_summary="{0} spaces found".format(count),
                    status="success",
                )
            ],
            request_id=request_id,
            error=None,
        )

    async def _book_space(
        self,
        arguments: Dict[str, Any],
        user_id: str,
        session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        if not session_id:
            return self._needs_more_info(
                "A conversation session is required before I can prepare a booking.",
                context,
                request_id,
            )

        explicit_space_id = self._value(arguments, "spaceId", "space_id")
        candidate = None
        if explicit_space_id is None:
            rank = self._value(arguments, "candidateRank", "candidate_rank") or 1
            try:
                candidate = context.resolve_space_rank(int(rank))
            except ContextResolutionError as error:
                return self._needs_more_info(str(error), context, request_id)
            space_id = candidate.space_id
        else:
            space_id = int(explicit_space_id)

        search_results = context.context.search_results
        start = self._value(arguments, "startDateTime", "start_date_time")
        end = self._value(arguments, "endDateTime", "end_date_time")
        if search_results is not None:
            start = start or search_results.start_date_time
            end = end or search_results.end_date_time
        if not start or not end:
            return self._needs_more_info(
                "Please provide both a start time and an end time.",
                context,
                request_id,
            )

        exact_arguments = {
            "spaceId": space_id,
            "startDateTime": start,
            "endDateTime": end,
        }
        check_availability = self._value(
            arguments, "checkAvailability", "check_availability"
        )
        if check_availability is not False:
            availability = await self._tool_client.call_tool(
                "check_availability", exact_arguments.copy()
            )
            if availability.get("success") is not True:
                return map_tool_result(
                    "check_availability",
                    availability,
                    context.snapshot(),
                    request_id,
                )
            availability_data = availability.get("data") or {}
            if (
                isinstance(availability_data, dict)
                and availability_data.get("available") is False
            ):
                reason = availability_data.get("reasonCode") or "SPACE_UNAVAILABLE"
                return map_tool_result(
                    "check_availability",
                    {
                        "success": False,
                        "data": availability_data,
                        "error": {
                            "code": reason,
                            "message": "The requested space is unavailable.",
                        },
                    },
                    context.snapshot(),
                    request_id,
                )

        space_name = candidate.name if candidate else "space {0}".format(space_id)
        preview = dict(exact_arguments)
        preview["spaceName"] = space_name
        return self._create_confirmation(
            user_id,
            session_id,
            "create_booking",
            exact_arguments,
            preview,
            "Book {0} from {1} to {2}".format(space_name, start, end),
            context,
            request_id,
        )

    async def _list_bookings(
        self,
        _arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        result = await self._tool_client.call_tool("list_user_bookings", {})
        if result.get("success") is not True:
            return map_tool_result(
                "list_user_bookings", result, context.snapshot(), request_id
            )
        bookings = result.get("data") or []
        if isinstance(bookings, dict):
            bookings = bookings.get("bookings") or []
        context.replace_booking_candidates(bookings)
        candidates = context.context.booking_candidates
        message = (
            "You have no bookings."
            if not candidates
            else "Your bookings are: {0}.".format(
                "; ".join(
                    "{0}. booking {1} ({2})".format(
                        candidate.rank,
                        candidate.booking_id,
                        candidate.status or "UNKNOWN",
                    )
                    for candidate in candidates
                )
            )
        )
        return InvokeResponse(
            response=message,
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="list_user_bookings",
                    result_summary="{0} bookings found".format(len(candidates)),
                    status="success",
                )
            ],
            request_id=request_id,
            error=None,
        )

    async def _cancel_booking(
        self,
        arguments: Dict[str, Any],
        user_id: str,
        session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        if not session_id:
            return self._needs_more_info(
                "A conversation session is required before I can prepare a cancellation.",
                context,
                request_id,
            )
        booking_id = self._value(arguments, "bookingId", "booking_id")
        candidate = None
        if booking_id is None:
            reference = self._value(arguments, "reference") or "that booking"
            rank = self._value(arguments, "candidateRank", "candidate_rank")
            try:
                candidate = context.resolve_booking_reference(
                    str(reference), int(rank) if rank is not None else None
                )
            except ContextResolutionError as error:
                return self._needs_more_info(str(error), context, request_id)
            booking_id = candidate.booking_id

        status_result = await self._tool_client.call_tool(
            "get_booking_status", {"bookingId": int(booking_id)}
        )
        if status_result.get("success") is not True:
            return map_tool_result(
                "get_booking_status",
                status_result,
                context.snapshot(),
                request_id,
            )
        booking = status_result.get("data") or {}
        booking_status = str(booking.get("status") or "").upper()
        if booking_status == "CANCELLED":
            return self._business_message(
                "That booking is already cancelled.",
                "cancel_booking",
                "BOOKING_CANCELLATION_NOT_ALLOWED",
                context,
                request_id,
            )
        if booking_status == "COMPLETED":
            return self._business_message(
                "A completed booking cannot be cancelled.",
                "cancel_booking",
                "BOOKING_CANCELLATION_NOT_ALLOWED",
                context,
                request_id,
            )
        if self._booking_has_started(booking.get("startDateTime")):
            return self._business_message(
                "A booking that has already started cannot be cancelled.",
                "cancel_booking",
                "BOOKING_CANCELLATION_NOT_ALLOWED",
                context,
                request_id,
            )

        space = booking.get("space") or {}
        preview = {
            "bookingId": int(booking_id),
            "spaceName": booking.get("spaceName") or space.get("name"),
            "startDateTime": booking.get("startDateTime")
            or (candidate.start_date_time if candidate else None),
            "endDateTime": booking.get("endDateTime")
            or (candidate.end_date_time if candidate else None),
        }
        return self._create_confirmation(
            user_id,
            session_id,
            "cancel_booking",
            {"bookingId": int(booking_id)},
            preview,
            "Cancel booking {0}".format(booking_id),
            context,
            request_id,
        )

    async def _submit_maintenance(
        self,
        arguments: Dict[str, Any],
        user_id: str,
        session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        if not session_id:
            return self._needs_more_info(
                "A conversation session is required before I can prepare a maintenance request.",
                context,
                request_id,
            )
        existing = context.context.pending_maintenance_info
        merged = existing.model_dump(by_alias=True) if existing else {}
        merged.update(
            self._pick(
                arguments,
                (
                    "spaceId",
                    "building",
                    "roomNumber",
                    "facilityType",
                    "description",
                    "priority",
                ),
            )
        )
        merged["priority"] = str(merged.get("priority") or "MEDIUM").upper()

        missing = []
        if not merged.get("facilityType"):
            missing.append("facilityType")
        if not merged.get("description"):
            missing.append("description")
        if not merged.get("spaceId") and not (
            merged.get("building") and merged.get("roomNumber")
        ):
            missing.append("location")
        pending = PendingMaintenanceInfo(
            spaceId=merged.get("spaceId"),
            building=merged.get("building"),
            roomNumber=merged.get("roomNumber"),
            facilityType=merged.get("facilityType"),
            description=merged.get("description"),
            priority=merged["priority"],
            missingFields=missing,
        )
        if missing:
            context.set_pending_maintenance(pending)
            return self._needs_more_info(
                self._maintenance_question(missing), context, request_id
            )

        if not merged.get("spaceId"):
            search_result = await self._tool_client.call_tool(
                "search_spaces",
                {
                    "query": str(merged["roomNumber"]),
                    "building": str(merged["building"]),
                },
            )
            if search_result.get("success") is not True:
                return map_tool_result(
                    "search_spaces", search_result, context.snapshot(), request_id
                )
            spaces = search_result.get("data") or []
            if isinstance(spaces, dict):
                spaces = spaces.get("spaces") or []
            if len(spaces) > 1:
                context.set_pending_maintenance(pending)
                return self._needs_more_info(
                    "More than one space matches that location. Please specify the exact room.",
                    context,
                    request_id,
                )
            if len(spaces) == 1:
                merged["spaceId"] = spaces[0].get("spaceId")

        exact_arguments = {
            "facilityType": merged["facilityType"],
            "description": merged["description"],
            "priority": merged["priority"],
        }
        if merged.get("spaceId"):
            exact_arguments["spaceId"] = int(merged["spaceId"])
        else:
            exact_arguments["building"] = merged["building"]
            exact_arguments["roomNumber"] = merged["roomNumber"]
        preview = dict(exact_arguments)
        return self._create_confirmation(
            user_id,
            session_id,
            "submit_maintenance_request",
            exact_arguments,
            preview,
            "Submit a {0} maintenance request for {1}".format(
                merged["priority"], merged["facilityType"]
            ),
            context,
            request_id,
        )

    async def _list_maintenance(
        self,
        _arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        result = await self._tool_client.call_tool("list_user_maintenance_requests", {})
        if result.get("success") is not True:
            return map_tool_result(
                "list_user_maintenance_requests",
                result,
                context.snapshot(),
                request_id,
            )
        tickets = result.get("data") or []
        if isinstance(tickets, dict):
            tickets = tickets.get("tickets") or []
        context.touch("list_user_maintenance_requests")
        if len(tickets) == 1 and tickets[0].get("ticketId") is not None:
            context.context.last_maintenance_ticket_id = int(tickets[0]["ticketId"])
        message = (
            "You have no maintenance requests."
            if not tickets
            else "Your maintenance requests are: {0}.".format(
                "; ".join(
                    "ticket {0} ({1})".format(
                        ticket.get("ticketId"), ticket.get("status") or "UNKNOWN"
                    )
                    for ticket in tickets
                )
            )
        )
        return InvokeResponse(
            response=message,
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="list_user_maintenance_requests",
                    result_summary="{0} maintenance requests found".format(
                        len(tickets)
                    ),
                    status="success",
                )
            ],
            request_id=request_id,
            error=None,
        )

    def _create_confirmation(
        self,
        user_id: str,
        session_id: str,
        tool_name: str,
        exact_arguments: Dict[str, Any],
        preview: Dict[str, Any],
        summary: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        pending = self._confirmation_store.create(
            user_id,
            session_id,
            tool_name,
            exact_arguments,
            preview,
        )
        context.touch(tool_name)
        return InvokeResponse(
            response="Please confirm: {0}.".format(summary),
            status="needs_confirmation",
            confirmation_required=ConfirmationRequired(
                confirmation_id=pending.confirmation_id,
                action=tool_name,
                summary=summary,
                preview=pending.preview_copy(),
                expires_at=pending.expires_at,
            ),
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action=tool_name,
                    params_summary=summary,
                    result_summary="Awaiting confirmation",
                    status="skipped",
                )
            ],
            request_id=request_id,
            error=None,
        )

    async def _resume_confirmation(
        self,
        request: InvokeRequest,
        user_id: str,
        session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        if not request.confirmation_id or not session_id:
            return self._needs_more_info(
                "A valid confirmation and conversation session are required.",
                context,
                request_id,
            )
        try:
            pending = self._confirmation_store.consume(
                request.confirmation_id,
                user_id,
                session_id,
            )
        except ConfirmationError:
            return self._needs_more_info(
                "That confirmation is invalid, expired, or already used. Please start the action again.",
                context,
                request_id,
            )
        result = await self._tool_client.call_tool(
            pending.tool_name, pending.arguments_copy()
        )
        if result.get("success") is not True:
            context.touch(pending.tool_name)
            return map_tool_result(
                pending.tool_name,
                result,
                context.snapshot(),
                request_id,
                params_summary=str(pending.preview_copy()),
            )
        return self._confirmed_success(pending, result, context, request_id)

    def _confirmed_success(
        self,
        pending: PendingAction,
        result: Dict[str, Any],
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        data = result.get("data") or {}
        if pending.tool_name == "create_booking":
            booking_id = data.get("bookingId")
            if booking_id is not None:
                context.set_last_booking(
                    int(booking_id), data.get("status") or "CONFIRMED"
                )
            message = "Your booking is confirmed. Booking ID: {0}.".format(booking_id)
        elif pending.tool_name == "cancel_booking":
            booking_id = data.get("bookingId") or pending.arguments_copy().get(
                "bookingId"
            )
            context.set_last_booking(int(booking_id), data.get("status") or "CANCELLED")
            message = "Booking {0} has been cancelled.".format(booking_id)
        else:
            ticket_id = data.get("ticketId")
            if ticket_id is not None:
                context.set_last_maintenance_ticket(int(ticket_id))
            else:
                context.clear_pending_maintenance()
            message = "Your maintenance request was submitted. Ticket ID: {0}.".format(
                ticket_id
            )
        return InvokeResponse(
            response=message,
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action=pending.tool_name,
                    params_summary=str(pending.preview_copy()),
                    result_summary="Facilities tool completed",
                    status="success",
                )
            ],
            request_id=request_id,
            error=None,
        )

    def _booking_has_started(self, start_date_time: Optional[str]) -> bool:
        if not start_date_time:
            return False
        try:
            parsed = datetime.fromisoformat(str(start_date_time))
        except ValueError:
            return False
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=CAMPUS_TIMEZONE)
        now = self._now_provider()
        if now.tzinfo is None:
            now = now.replace(tzinfo=CAMPUS_TIMEZONE)
        return parsed <= now.astimezone(CAMPUS_TIMEZONE)

    @staticmethod
    def _maintenance_question(missing):
        if "location" in missing:
            return "Which building and room is the problem in?"
        if "facilityType" in missing:
            return "Which facility or equipment is affected?"
        return "Please describe the maintenance problem."

    @staticmethod
    def _needs_more_info(
        message: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        return InvokeResponse(
            response=message,
            status="needs_more_info",
            shared_context=context.snapshot(),
            actions_taken=[],
            request_id=request_id,
            error=None,
        )

    @staticmethod
    def _business_message(
        message: str,
        action: str,
        error_code: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        return InvokeResponse(
            response=message,
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action=action,
                    result_summary=message,
                    error_code=error_code,
                    status="failed",
                )
            ],
            request_id=request_id,
            error=None,
        )
