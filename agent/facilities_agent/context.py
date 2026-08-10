"""Bounded, replace-on-update Facilities conversation context."""

from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Iterable, List, Optional

from pydantic import ValidationError

from .models import (
    BookingCandidate,
    FacilitiesSharedContext,
    PendingMaintenanceInfo,
    SearchResultsContext,
    SpaceCandidate,
)


class ContextResolutionError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


class FacilitiesContextManager:
    SEARCH_LIMIT = 5
    BOOKING_LIMIT = 10

    def __init__(
        self,
        context: Optional[FacilitiesSharedContext] = None,
        now_provider=utc_now,
    ) -> None:
        self.context = context or FacilitiesSharedContext()
        self._now_provider = now_provider

    @classmethod
    def from_shared_data(cls, shared_data: Dict[str, Any], now_provider=utc_now):
        raw = (shared_data or {}).get("facilities")
        if not isinstance(raw, dict):
            return cls(now_provider=now_provider)
        try:
            return cls(
                FacilitiesSharedContext.model_validate(raw), now_provider=now_provider
            )
        except ValidationError:
            # Untrusted or stale shared context must never break an invocation.
            return cls(now_provider=now_provider)

    def _now(self) -> datetime:
        return _aware_utc(self._now_provider())

    def touch(self, intent: Optional[str] = None) -> None:
        if intent is not None:
            self.context.last_intent = intent
        self.context.updated_at = self._now()

    def snapshot(self) -> Dict[str, Any]:
        """Return the complete bounded Facilities snapshot used by Chat Core."""
        return {
            "facilities": self.context.model_dump(
                by_alias=True,
                mode="json",
                exclude_none=True,
            )
        }

    def replace_search_results(
        self,
        spaces: Iterable[Dict[str, Any]],
        start_date_time: Optional[str],
        end_date_time: Optional[str],
        ttl_seconds: int = 3600,
    ) -> None:
        candidates: List[SpaceCandidate] = []
        for rank, space in enumerate(list(spaces)[: self.SEARCH_LIMIT], start=1):
            candidates.append(
                SpaceCandidate(
                    rank=rank,
                    spaceId=space.get("spaceId"),
                    name=space.get("name") or "Unknown space",
                    building=space.get("building"),
                    roomNumber=space.get("roomNumber"),
                    spaceType=space.get("spaceType"),
                    capacity=space.get("capacity"),
                    equipment=list(space.get("equipment") or []),
                )
            )
        self.context.search_results = SearchResultsContext(
            startDateTime=start_date_time,
            endDateTime=end_date_time,
            expiresAt=self._now() + timedelta(seconds=ttl_seconds),
            candidates=candidates,
        )
        self.context.selected_space = None
        self.touch("search_spaces")

    def search_is_expired(self) -> bool:
        results = self.context.search_results
        if results is None:
            return True
        return _aware_utc(results.expires_at) <= self._now()

    def resolve_space_rank(self, rank: int) -> SpaceCandidate:
        if self.search_is_expired():
            raise ContextResolutionError(
                "SEARCH_CONTEXT_EXPIRED",
                "The previous search results have expired.",
            )
        for candidate in self.context.search_results.candidates:
            if candidate.rank == rank:
                self.context.selected_space = candidate.model_copy(deep=True)
                return candidate.model_copy(deep=True)
        raise ContextResolutionError(
            "SPACE_CANDIDATE_NOT_FOUND",
            "That search result is no longer available.",
        )

    def replace_booking_candidates(self, bookings: Iterable[Dict[str, Any]]) -> None:
        candidates: List[BookingCandidate] = []
        for rank, booking in enumerate(list(bookings)[: self.BOOKING_LIMIT], start=1):
            space = booking.get("space") or {}
            candidates.append(
                BookingCandidate(
                    rank=rank,
                    bookingId=booking.get("bookingId"),
                    spaceId=booking.get("spaceId") or space.get("spaceId"),
                    spaceName=booking.get("spaceName") or space.get("name"),
                    startDateTime=booking.get("startDateTime"),
                    endDateTime=booking.get("endDateTime"),
                    status=booking.get("status"),
                )
            )
        self.context.booking_candidates = candidates
        self.context.last_booking_id = (
            candidates[0].booking_id if len(candidates) == 1 else None
        )
        self.touch("list_user_bookings")

    def resolve_booking_reference(
        self,
        reference: str,
        rank: Optional[int] = None,
    ) -> BookingCandidate:
        normalized = (reference or "").strip().lower()
        candidates = self.context.booking_candidates

        if rank is not None or normalized in {"first", "first one"}:
            target_rank = rank or 1
            for candidate in candidates:
                if candidate.rank == target_rank:
                    return candidate.model_copy(deep=True)
            raise ContextResolutionError(
                "BOOKING_CANDIDATE_NOT_FOUND",
                "That booking is not present in the recent list.",
            )

        if normalized in {"that", "that booking", "it"}:
            if self.context.last_booking_id is not None:
                for candidate in candidates:
                    if candidate.booking_id == self.context.last_booking_id:
                        return candidate.model_copy(deep=True)
                return BookingCandidate(
                    rank=1,
                    bookingId=self.context.last_booking_id,
                )
            if len(candidates) == 1:
                return candidates[0].model_copy(deep=True)
            raise ContextResolutionError(
                "AMBIGUOUS_BOOKING_REFERENCE",
                "More than one booking could match that reference.",
            )

        raise ContextResolutionError(
            "BOOKING_REFERENCE_NOT_FOUND",
            "Please provide a booking ID or list position.",
        )

    def set_last_booking(self, booking_id: int, status: Optional[str] = None) -> None:
        self.context.last_booking_id = booking_id
        if status is not None:
            for candidate in self.context.booking_candidates:
                if candidate.booking_id == booking_id:
                    candidate.status = status
        self.touch()

    def set_pending_maintenance(self, info: PendingMaintenanceInfo) -> None:
        self.context.pending_maintenance_info = info.model_copy(deep=True)
        self.touch("submit_maintenance_request")

    def clear_pending_maintenance(self) -> None:
        self.context.pending_maintenance_info = None
        self.touch()

    def set_last_maintenance_ticket(self, ticket_id: int) -> None:
        self.context.last_maintenance_ticket_id = ticket_id
        self.context.pending_maintenance_info = None
        self.touch("submit_maintenance_request")
