"""Deterministic orchestration core for the Facilities Domain Agent Adapter."""

import logging
import re
import secrets
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, Optional

logger = logging.getLogger(__name__)

from .confirmation import ConfirmationError, ConfirmationStore, PendingAction
from .context import ContextResolutionError, FacilitiesContextManager
from .datetime_parser import (
    CAMPUS_TIMEZONE,
    FacilitiesDateTimeParser,
    singapore_now,
)
from .models import (
    ActionTaken,
    ConfirmationRequired,
    InvokeRequest,
    InvokeResponse,
    PendingMaintenanceInfo,
)
from .planner import (
    FacilitiesPlanner,
    PlannerConfigurationError,
    PlannerDecision,
    PlannerError,
)
from .result_mapper import map_technical_error, map_tool_result
from .tool_client import ToolClient, ToolClientError


class FacilitiesAdapterService:
    """Coordinates planner decisions and tool calls without owning business rules."""

    def __init__(
        self,
        planner: Optional[FacilitiesPlanner],
        tool_client: ToolClient,
        confirmation_store: Optional[ConfirmationStore] = None,
        now_provider=singapore_now,
    ) -> None:
        self._planner = planner
        self._tool_client = tool_client
        self._now_provider = now_provider
        self._datetime_parser = FacilitiesDateTimeParser(now_provider)
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
        user_id = str(authenticated_user_id)

        try:
            if request.confirmed:
                return await self._resume_confirmation(
                    request,
                    user_id,
                    session_id,
                    context,
                    invocation_id,
                )

            if self._previous_turn_abandoned(request):
                context.clear_pending_booking()
            pending_booking = context.get_pending_booking(user_id, session_id)
            if pending_booking is not None and self._is_abandonment(request.message):
                context.clear_pending_booking()
                return InvokeResponse(
                    response="Okay, I cancelled that unfinished booking request.",
                    status="completed",
                    shared_context=context.snapshot(),
                    actions_taken=[],
                    request_id=invocation_id,
                    error=None,
                )
            if pending_booking is not None and self._is_booking_continuation(
                request.message
            ):
                return await self._book_space(
                    self._deterministic_booking_arguments(request.message),
                    user_id,
                    session_id,
                    context,
                    invocation_id,
                )

            if self._planner is None:
                raise PlannerConfigurationError(
                    "Facilities planner is required for a new request"
                )
            decision = await self._planner.plan(
                request, context.context.model_copy(deep=True)
            )
            return await self._dispatch(
                decision,
                request.message,
                user_id,
                session_id,
                context,
                invocation_id,
            )
        except (ToolClientError, PlannerError) as error:
            context.clear_pending_booking()
            logger.warning(
                "Facilities service invoke failed: invocation_id=%s code=%s detail=%s",
                invocation_id, getattr(error, "code", "?"), error,
            )
            return map_technical_error(error, context.snapshot(), invocation_id)
        except Exception as error:  # Adapter exceptions are true system failures.
            context.clear_pending_booking()
            logger.exception(
                "Facilities service invoke crashed: invocation_id=%s",
                invocation_id,
            )
            return map_technical_error(error, context.snapshot(), invocation_id)

    def _utc_now(self):
        current = self._now_provider()
        if current.tzinfo is None:
            current = current.replace(tzinfo=CAMPUS_TIMEZONE)
        return current.astimezone(timezone.utc)

    async def _dispatch(
        self,
        decision: PlannerDecision,
        user_message: str,
        user_id: str,
        session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        arguments = dict(decision.arguments)
        if decision.datetime_text:
            arguments["datetimeText"] = decision.datetime_text

        if decision.intent == "create_booking":
            reference_arguments = self._booking_reference_arguments(
                user_message,
                allow_room_rank=not context.search_is_expired(),
            )
            has_reference_context = bool(
                context.context.selected_space is not None
                or (
                    context.context.search_results is not None
                    and not context.search_is_expired()
                    and context.context.search_results.candidates
                )
            )
            should_override_planner_space = bool(
                reference_arguments
                and (
                    "reference" not in reference_arguments
                    or has_reference_context
                )
            )
            if should_override_planner_space:
                for key in (
                    "spaceId",
                    "space_id",
                    "candidateRank",
                    "candidate_rank",
                    "reference",
                ):
                    arguments.pop(key, None)
                arguments.update(reference_arguments)
            elif not self._has_explicit_space_reference(user_message):
                # Never accept a planner-selected room without current-turn evidence.
                for key in (
                    "spaceId",
                    "space_id",
                    "candidateRank",
                    "candidate_rank",
                    "reference",
                ):
                    arguments.pop(key, None)
            return await self._book_space(
                arguments, user_id, session_id, context, request_id
            )

        context.clear_pending_booking()
        if decision.missing_fields:
            return self._needs_more_info(
                decision.clarification
                or "Please provide: {0}.".format(
                    ", ".join(decision.missing_fields)
                ),
                context,
                request_id,
            )
        handlers = {
            "search_spaces": self._search_spaces,
            "get_space_details": self._get_space_details,
            "check_availability": self._check_availability,
            "list_user_bookings": self._list_bookings,
            "get_booking_status": self._get_booking_status,
            "cancel_booking": self._cancel_booking,
            "submit_maintenance_request": self._submit_maintenance,
            "get_maintenance_status": self._get_maintenance_status,
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
            arguments, user_id, session_id, context, request_id
        )

    @staticmethod
    def _has_explicit_space_reference(message: str) -> bool:
        """Require current-turn evidence before accepting a planner-selected space.

        The planner sees conversation context and may otherwise copy a stale selected
        space into ``spaceId`` even when the current user message only supplies a new
        date/time. This guard validates reference provenance without trusting the
        planner's derived ID.
        """

        text = (message or "").strip()
        if not text:
            return False
        patterns = (
            r"^\s*(?:option|room)?\s*[1-5]\s*[.!?]?\s*$",
            r"\boption\s*(?:number\s*)?(?:\d+|one|two|three|four|five)\b",
            r"\broom\s*(?:number\s*)?(?:[1-5]|one|two|three|four|five)\b",
            r"\b(?:book|reserve)\s+(?:the\s+)?(?:first|second|third|fourth|fifth)\b",
            r"\b(?:first|second|third|fourth|fifth)\s+(?:one|room|space|option|result)\b",
            r"\b(?:it|that one|this one|the room|this room|that room)\b",
            r"\b(?:this|that|selected|same|the)\s+(?:room|space|pod|lab|venue)\b",
            r"\b(?:room|space|pod|lab|hall|court|studio)\s*(?:id\s*)?[#:]?\s*(?:\d+[a-z0-9-]*|[a-z]+\d+[a-z0-9-]*|[a-z])\b",
            r"\b[a-z]{2,}\d*(?:-\d+){1,}\b",
            r"第\s*[一二三四五六七八九十\d]+\s*个",
            r"(?:这个|那个|刚才那个)(?:房间|空间|场地|实验室)",
            r"(?:房间|空间|场地|实验室)\s*(?:编号|id)?\s*[a-z]?\d+",
        )
        return any(re.search(pattern, text, re.IGNORECASE) for pattern in patterns)

    @staticmethod
    def _booking_reference_arguments(
        message: str, *, allow_room_rank: bool = False
    ) -> Dict[str, Any]:
        text = (message or "").strip().lower()
        rank_words = {
            "one": 1,
            "first": 1,
            "two": 2,
            "second": 2,
            "three": 3,
            "third": 3,
            "four": 4,
            "fourth": 4,
            "five": 5,
            "fifth": 5,
        }
        rank_prefix = r"(?:option|room)" if allow_room_rank else r"option"
        rank_match = re.search(
            (
                r"(?:^|\b){0}\s*(?:number\s*)?"
                r"(?P<rank>[1-5]|one|two|three|four|five)(?:\b|$)"
            ).format(rank_prefix),
            text,
        )
        if rank_match is None:
            rank_match = re.search(
                r"(?:^|\b)(?P<rank>first|second|third|fourth|fifth)\s*(?:one|room|space|option|result)?(?:\b|$)",
                text,
            )
        if rank_match is None:
            rank_match = re.fullmatch(r"\s*(?P<rank>[1-5])\s*[.!?]?\s*", text)
        if rank_match is not None:
            raw_rank = rank_match.group("rank")
            rank = rank_words.get(
                raw_rank, int(raw_rank) if raw_rank.isdigit() else None
            )
            return {"candidateRank": rank}

        explicit_id = re.search(r"\bspace\s*id\s*[#:]?\s*(?P<id>\d+)\b", text)
        if explicit_id is not None:
            return {"spaceId": int(explicit_id.group("id"))}

        vague_references = (
            "it",
            "that one",
            "this one",
            "the room",
            "this room",
            "that room",
            "that",
            "that space",
            "刚才那个",
            "那个房间",
        )
        for reference in vague_references:
            if re.search(r"(?<!\w){0}(?!\w)".format(re.escape(reference)), text):
                return {"reference": reference}
        return {}

    @staticmethod
    def _has_datetime_evidence(message: str) -> bool:
        text = (message or "").strip()
        patterns = (
            r"\b(?:from|at|on|until|to|today|tomorrow|next\s+(?:mon|tues|wednes|thurs|fri|satur|sun)day)\b",
            r"\b\d{1,2}(?::\d{2})?\s*(?:a\.?m\.?|p\.?m\.?)\b",
            r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)",
            r"(?<![\d.])\d{1,2}[./]\d{1,2}(?![\d.])",
            r"(?:今天|明天|下周|上午|下午|晚上|\d{1,2}点)",
        )
        return any(re.search(pattern, text, re.IGNORECASE) for pattern in patterns)

    @classmethod
    def _is_booking_continuation(cls, message: str) -> bool:
        return bool(
            cls._booking_reference_arguments(message, allow_room_rank=True)
            or cls._has_datetime_evidence(message)
        )

    @staticmethod
    def _is_abandonment(message: str) -> bool:
        text = (message or "").strip().lower()
        return any(
            phrase in text
            for phrase in (
                "cancel that",
                "never mind",
                "nevermind",
                "forget it",
                "算了",
                "不用了",
                "放弃",
                "取消这个",
            )
        )

    @classmethod
    def _previous_turn_abandoned(cls, request: InvokeRequest) -> bool:
        recent = request.conversation_context.shared_data.get("recent_messages")
        if not isinstance(recent, list):
            return False
        user_messages = [
            str(item.get("content") or "")
            for item in recent
            if isinstance(item, dict) and item.get("role") == "user"
        ]
        if not user_messages:
            return False
        if user_messages[-1].strip() == request.message.strip():
            user_messages = user_messages[:-1]
        return bool(user_messages and cls._is_abandonment(user_messages[-1]))

    @classmethod
    def _deterministic_booking_arguments(cls, message: str) -> Dict[str, Any]:
        arguments = cls._booking_reference_arguments(message, allow_room_rank=True)
        if cls._has_datetime_evidence(message):
            arguments["datetimeText"] = message
        return arguments

    @staticmethod
    def _value(
        arguments: Dict[str, Any], camel: str, snake: Optional[str] = None
    ) -> Any:
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

    def _resolve_space_id(
        self,
        arguments: Dict[str, Any],
        context: FacilitiesContextManager,
    ) -> tuple[Optional[int], Optional[str]]:
        explicit = self._value(arguments, "spaceId", "space_id")
        if explicit is not None:
            try:
                value = int(explicit)
            except (TypeError, ValueError):
                return None, "Please provide a valid numeric space ID."
            if value <= 0:
                return None, "Please provide a valid numeric space ID."
            return value, None

        rank = self._value(arguments, "candidateRank", "candidate_rank")
        reference = str(self._value(arguments, "reference") or "").strip().lower()
        if rank is None and reference in {"first", "first one", "第一个", "刚才第一个"}:
            rank = 1
        if rank is not None:
            try:
                candidate = context.resolve_space_rank(int(rank))
                return candidate.space_id, None
            except (ContextResolutionError, TypeError, ValueError) as error:
                return None, str(error)
        if reference in {
            "that",
            "that one",
            "this one",
            "the room",
            "this room",
            "that room",
            "that space",
            "it",
            "刚才那个",
            "那个房间",
        }:
            selected = context.context.selected_space
            if selected is not None:
                return selected.space_id, None
            results = context.context.search_results
            if results is not None and len(results.candidates) == 1:
                return results.candidates[0].space_id, None
            if results is not None and len(results.candidates) > 1:
                return None, "Which room would you like to book? Please choose a numbered search result."
        return None, "Please provide a space ID or choose a recent search result."

    def _explicit_booking_date(self, text: str) -> Optional[str]:
        date_patterns = (
            r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)",
            r"(?<![\d.])\d{1,2}[./]\d{1,2}(?![\d.])",
            r"\b(?:today|tomorrow|next\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b",
            r"(?:今天|明天|下周[一二三四五六日天])",
        )
        if not any(re.search(pattern, text or "", re.IGNORECASE) for pattern in date_patterns):
            return None
        return self._datetime_parser.parse_date(text).isoformat()

    @staticmethod
    def _candidate_for_space(
        space_id: Optional[int], context: FacilitiesContextManager
    ):
        if space_id is None:
            return None
        selected = context.context.selected_space
        if selected is not None and selected.space_id == space_id:
            return selected.model_copy(deep=True)
        results = context.context.search_results
        if results is not None:
            for candidate in results.candidates:
                if candidate.space_id == space_id:
                    return candidate.model_copy(deep=True)
        return None

    def _normalized_time_range(
        self,
        arguments: Dict[str, Any],
        *,
        required: bool,
    ) -> tuple[Optional[Dict[str, str]], Optional[str]]:
        datetime_text = self._value(arguments, "datetimeText", "datetime_text")
        start = self._value(arguments, "startDateTime", "start_date_time")
        end = self._value(arguments, "endDateTime", "end_date_time")

        if datetime_text:
            parsed = self._datetime_parser.parse(str(datetime_text))
            if parsed.needs_clarification:
                return None, parsed.clarification or "Please clarify the requested time."
            start = parsed.start_local_iso
            end = parsed.end_local_iso

        if not start and not end:
            if required:
                return None, "Please provide a date and both a start and end time."
            return {}, None
        if not start or not end:
            return None, "Please provide both a start time and an end time."

        try:
            start_value = datetime.fromisoformat(str(start))
            end_value = datetime.fromisoformat(str(end))
        except (TypeError, ValueError):
            return None, "Please provide a valid date and time."
        if start_value.tzinfo is not None or end_value.tzinfo is not None:
            return None, "Please provide Singapore local time without a timezone offset."
        if end_value <= start_value:
            return None, "The end time must be after the start time."
        now = self._now_provider()
        if now.tzinfo is None:
            now = now.replace(tzinfo=CAMPUS_TIMEZONE)
        if start_value.replace(tzinfo=CAMPUS_TIMEZONE) <= now.astimezone(CAMPUS_TIMEZONE):
            return None, "Please provide a future date and time."
        return {
            "startDateTime": start_value.isoformat(timespec="seconds"),
            "endDateTime": end_value.isoformat(timespec="seconds"),
        }, None

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
        time_arguments, clarification = self._normalized_time_range(
            arguments, required=False
        )
        if clarification:
            return self._needs_more_info(clarification, context, request_id)
        tool_arguments.update(time_arguments or {})
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

    async def _get_space_details(
        self,
        arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        space_id, clarification = self._resolve_space_id(arguments, context)
        if clarification:
            return self._needs_more_info(clarification, context, request_id)
        result = await self._tool_client.call_tool(
            "get_space_details", {"spaceId": space_id}
        )
        context.touch("get_space_details")
        if result.get("success") is not True:
            return map_tool_result(
                "get_space_details", result, context.snapshot(), request_id
            )
        data = result.get("data") or {}
        name = data.get("name") if isinstance(data, dict) else None
        return InvokeResponse(
            response="Details for {0} are ready.".format(name or "space {0}".format(space_id)),
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="get_space_details",
                    result_summary="Space details retrieved",
                    status="success",
                )
            ],
            request_id=request_id,
            error=None,
        )

    async def _check_availability(
        self,
        arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        space_id, clarification = self._resolve_space_id(arguments, context)
        if clarification:
            return self._needs_more_info(clarification, context, request_id)
        time_arguments, clarification = self._normalized_time_range(
            arguments, required=True
        )
        if clarification:
            return self._needs_more_info(clarification, context, request_id)
        tool_arguments = {"spaceId": space_id, **(time_arguments or {})}
        result = await self._tool_client.call_tool(
            "check_availability", tool_arguments
        )
        context.touch("check_availability")
        if result.get("success") is not True:
            return map_tool_result(
                "check_availability", result, context.snapshot(), request_id
            )
        data = result.get("data") or {}
        available = isinstance(data, dict) and data.get("available") is True
        message = (
            "That space is available for the requested time."
            if available
            else "That space is not available for the requested time."
        )
        return InvokeResponse(
            response=message,
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="check_availability",
                    result_summary="Available" if available else "Unavailable",
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

        pending = context.get_pending_booking(user_id, session_id)
        space_id = pending.space_id if pending is not None else None
        booking_date = pending.booking_date if pending is not None else None
        start = pending.start_date_time if pending is not None else None
        end = pending.end_date_time if pending is not None else None

        has_current_space = any(
            self._value(arguments, key, self._camel_to_snake(key)) is not None
            for key in ("spaceId", "candidateRank", "reference")
        )
        references_search_candidate = any(
            self._value(arguments, key, self._camel_to_snake(key)) is not None
            for key in ("candidateRank", "reference")
        )
        space_clarification = None
        if has_current_space:
            space_id, space_clarification = self._resolve_space_id(arguments, context)
        candidate = self._candidate_for_space(space_id, context)

        current_datetime_text = self._value(
            arguments, "datetimeText", "datetime_text"
        )
        current_start = self._value(arguments, "startDateTime", "start_date_time")
        current_end = self._value(arguments, "endDateTime", "end_date_time")
        time_clarification = None
        if current_datetime_text:
            raw_datetime_text = str(current_datetime_text)
            explicit_date = self._explicit_booking_date(raw_datetime_text)
            if explicit_date is not None:
                booking_date = explicit_date
                # A newly supplied date must not retain a prior draft's times.
                start = None
                end = None
            parse_text = raw_datetime_text
            if explicit_date is None and booking_date is not None:
                parse_text = "{0} {1}".format(booking_date, raw_datetime_text)
            time_arguments, time_clarification = self._normalized_time_range(
                {"datetimeText": parse_text}, required=False
            )
            if time_arguments:
                start = time_arguments["startDateTime"]
                end = time_arguments["endDateTime"]
                booking_date = start.split("T", 1)[0]
                time_clarification = None
        elif current_start is not None or current_end is not None:
            time_arguments, time_clarification = self._normalized_time_range(
                {
                    "startDateTime": current_start,
                    "endDateTime": current_end,
                },
                required=False,
            )
            if time_arguments:
                start = time_arguments["startDateTime"]
                end = time_arguments["endDateTime"]
                booking_date = start.split("T", 1)[0]

        search_results = context.context.search_results
        # Reuse a search window only when this turn explicitly references one of
        # those search candidates. A bare booking request must never inherit stale
        # time arguments from an earlier turn.
        if (
            not start
            and not end
            and current_datetime_text is None
            and current_start is None
            and current_end is None
            and pending is None
            and search_results is not None
            and candidate is not None
            and references_search_candidate
        ):
            inherited = {
                "startDateTime": search_results.start_date_time,
                "endDateTime": search_results.end_date_time,
            }
            time_arguments, time_clarification = self._normalized_time_range(
                inherited, required=True
            )
            if time_arguments:
                start = time_arguments["startDateTime"]
                end = time_arguments["endDateTime"]
                booking_date = start.split("T", 1)[0]
                time_clarification = None

        missing_fields = []
        if space_id is None:
            missing_fields.append("spaceId")
        if not booking_date:
            missing_fields.append("date")
        if not start:
            missing_fields.append("startDateTime")
        if not end:
            missing_fields.append("endDateTime")
        if missing_fields:
            context.set_pending_booking(
                user_id=user_id,
                session_id=session_id,
                space_id=space_id,
                booking_date=booking_date,
                start_date_time=start,
                end_date_time=end,
                missing_fields=missing_fields,
            )
            if space_id is None:
                return self._needs_more_info(
                    space_clarification
                    or "Which room would you like to book? Please choose a numbered search result or provide a space ID.",
                    context,
                    request_id,
                )
            if booking_date and (not start or not end):
                return self._needs_more_info(
                    "What start and end time would you like for {0}?".format(
                        booking_date
                    ),
                    context,
                    request_id,
                )
            return self._needs_more_info(
                time_clarification
                or "Please provide a date and both a start and end time.",
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
                context.clear_pending_booking()
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
                context.clear_pending_booking()
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
        context.clear_pending_booking()
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

    async def _get_booking_status(
        self,
        arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        booking_id = self._value(arguments, "bookingId", "booking_id")
        if booking_id is None:
            reference = self._value(arguments, "reference") or "that booking"
            rank = self._value(arguments, "candidateRank", "candidate_rank")
            try:
                candidate = context.resolve_booking_reference(
                    str(reference), int(rank) if rank is not None else None
                )
                booking_id = candidate.booking_id
            except (ContextResolutionError, TypeError, ValueError) as error:
                return self._needs_more_info(str(error), context, request_id)
        result = await self._tool_client.call_tool(
            "get_booking_status", {"bookingId": int(booking_id)}
        )
        context.touch("get_booking_status")
        if result.get("success") is not True:
            return map_tool_result(
                "get_booking_status", result, context.snapshot(), request_id
            )
        data = result.get("data") or {}
        status = data.get("status") if isinstance(data, dict) else None
        context.set_last_booking(int(booking_id), status)
        return InvokeResponse(
            response="Booking {0} is {1}.".format(booking_id, status or "UNKNOWN"),
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="get_booking_status",
                    result_summary="Booking status retrieved",
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
        if not merged.get("spaceId") and (
            self._value(arguments, "candidateRank", "candidate_rank") is not None
            or self._value(arguments, "reference") is not None
        ):
            resolved_space_id, _clarification = self._resolve_space_id(
                arguments, context
            )
            if resolved_space_id is not None:
                merged["spaceId"] = resolved_space_id
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

    async def _get_maintenance_status(
        self,
        arguments: Dict[str, Any],
        _user_id: str,
        _session_id: str,
        context: FacilitiesContextManager,
        request_id: str,
    ) -> InvokeResponse:
        ticket_id = self._value(arguments, "ticketId", "ticket_id")
        if ticket_id is None:
            reference = str(self._value(arguments, "reference") or "").lower()
            if reference in {
                "that",
                "that ticket",
                "it",
                "latest",
                "last",
                "那个工单",
            }:
                ticket_id = context.context.last_maintenance_ticket_id
        if ticket_id is None:
            return self._needs_more_info(
                "Please provide a maintenance ticket ID.", context, request_id
            )
        result = await self._tool_client.call_tool(
            "get_maintenance_status", {"ticketId": int(ticket_id)}
        )
        context.touch("get_maintenance_status")
        if result.get("success") is not True:
            return map_tool_result(
                "get_maintenance_status", result, context.snapshot(), request_id
            )
        data = result.get("data") or {}
        status = data.get("status") if isinstance(data, dict) else None
        context.set_last_maintenance_ticket(int(ticket_id))
        return InvokeResponse(
            response="Maintenance ticket {0} is {1}.".format(
                ticket_id, status or "UNKNOWN"
            ),
            status="completed",
            shared_context=context.snapshot(),
            actions_taken=[
                ActionTaken(
                    action="get_maintenance_status",
                    result_summary="Maintenance status retrieved",
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
