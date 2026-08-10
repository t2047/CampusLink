import unittest
from datetime import datetime, timedelta

from agent.facilities_agent.confirmation import ConfirmationStore
from agent.facilities_agent.datetime_parser import CAMPUS_TIMEZONE
from agent.facilities_agent.models import InvokeRequest
from agent.facilities_agent.planner import (
    FACILITIES_INTENTS,
    FakePlanner,
    PlannerDecision,
)
from agent.facilities_agent.service import FacilitiesAdapterService
from agent.facilities_agent.tool_client import (
    FACILITIES_TOOL_NAMES,
    FakeFacilitiesToolClient,
)


class MutableClock:
    def __init__(self, value):
        self.value = value

    def __call__(self):
        return self.value


def request(
    message,
    shared_context=None,
    session_id="session-1",
    confirmed=False,
    confirmation_id=None,
):
    return InvokeRequest(
        message=message,
        conversation_context={
            "session_id": session_id,
            "shared_data": shared_context or {},
        },
        confirmed=confirmed,
        confirmation_id=confirmation_id,
    )


class FacilitiesAdapterServiceTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.clock = MutableClock(datetime(2026, 8, 9, 10, 0, tzinfo=CAMPUS_TIMEZONE))

    def build_service(self, decisions, fixtures):
        planner = FakePlanner(decisions)
        tool_client = FakeFacilitiesToolClient(fixtures)
        store = ConfirmationStore(now_provider=self.clock)
        service = FacilitiesAdapterService(
            planner,
            tool_client,
            store,
            now_provider=self.clock,
        )
        return service, planner, tool_client, store

    async def prepare_booking(self, create_response=None):
        decisions = {
            "search": PlannerDecision(
                intent="search_spaces",
                arguments={
                    "spaceType": "STUDY_ROOM",
                    "startDateTime": "2026-08-10T14:00:00",
                    "endDateTime": "2026-08-10T16:00:00",
                },
            ),
            "book first": PlannerDecision(
                intent="create_booking",
                arguments={"candidateRank": 1},
            ),
        }
        fixtures = {
            "search_spaces": {
                "success": True,
                "data": [
                    {
                        "spaceId": 4,
                        "name": "COM2 Project Room 03",
                        "building": "COM2",
                        "roomNumber": "03",
                        "spaceType": "STUDY_ROOM",
                        "capacity": 6,
                        "equipment": ["projector"],
                    }
                ],
                "error": None,
            },
            "check_availability": {
                "success": True,
                "data": {"available": True},
                "error": None,
            },
            "create_booking": create_response
            or {
                "success": True,
                "data": {"bookingId": 123, "status": "CONFIRMED"},
                "error": None,
            },
        }
        service, planner, client, store = self.build_service(decisions, fixtures)
        search_response = await service.invoke(request("search"), "42", "search-req")
        confirmation_response = await service.invoke(
            request("book first", search_response.shared_context),
            "42",
            "book-req",
        )
        return service, planner, client, store, confirmation_response

    async def test_demo_a_search_book_confirm_and_frozen_preview(self):
        service, planner, client, store, confirmation = await self.prepare_booking()
        self.assertEqual("needs_confirmation", confirmation.status)
        self.assertEqual(2, len(planner.calls))
        confirmation_id = confirmation.confirmation_required.confirmation_id
        pending = store.get(confirmation_id, "42", "session-1")
        exact = pending.arguments_copy()
        self.assertNotIn("userId", exact)
        self.assertNotIn("user_id", exact)
        preview = pending.preview_copy()
        self.assertEqual(exact["spaceId"], preview["spaceId"])
        self.assertEqual(exact["startDateTime"], preview["startDateTime"])
        self.assertEqual(exact["endDateTime"], preview["endDateTime"])

        completed = await service.invoke(
            request(
                "this message must not be replanned",
                confirmation.shared_context,
                confirmed=True,
                confirmation_id=confirmation_id,
            ),
            "42",
            "confirm-req",
        )
        self.assertEqual("completed", completed.status)
        self.assertIn("123", completed.response)
        self.assertEqual(2, len(planner.calls), "confirmation must bypass planner")
        create_call = [
            call for call in client.calls if call["tool_name"] == "create_booking"
        ]
        self.assertEqual([exact], [call["arguments"] for call in create_call])
        self.assertEqual(123, completed.shared_context["facilities"]["last_booking_id"])

    async def test_wrong_user_does_not_execute_write_tool(self):
        service, _planner, client, _store, confirmation = await self.prepare_booking()
        response = await service.invoke(
            request(
                "yes",
                confirmation.shared_context,
                confirmed=True,
                confirmation_id=confirmation.confirmation_required.confirmation_id,
            ),
            "99",
        )
        self.assertEqual("needs_more_info", response.status)
        self.assertFalse(
            any(call["tool_name"] == "create_booking" for call in client.calls)
        )

    async def test_wrong_session_does_not_execute_write_tool(self):
        service, _planner, client, _store, confirmation = await self.prepare_booking()
        response = await service.invoke(
            request(
                "yes",
                confirmation.shared_context,
                session_id="different-session",
                confirmed=True,
                confirmation_id=confirmation.confirmation_required.confirmation_id,
            ),
            "42",
        )
        self.assertEqual("needs_more_info", response.status)
        self.assertFalse(
            any(call["tool_name"] == "create_booking" for call in client.calls)
        )

    async def test_expired_confirmation_does_not_execute_write_tool(self):
        service, _planner, client, _store, confirmation = await self.prepare_booking()
        self.clock.value += timedelta(seconds=601)
        response = await service.invoke(
            request(
                "yes",
                confirmation.shared_context,
                confirmed=True,
                confirmation_id=confirmation.confirmation_required.confirmation_id,
            ),
            "42",
        )
        self.assertEqual("needs_more_info", response.status)
        self.assertFalse(
            any(call["tool_name"] == "create_booking" for call in client.calls)
        )

    async def test_consumed_confirmation_does_not_execute_twice(self):
        service, _planner, client, _store, confirmation = await self.prepare_booking()
        confirmation_request = request(
            "yes",
            confirmation.shared_context,
            confirmed=True,
            confirmation_id=confirmation.confirmation_required.confirmation_id,
        )
        first = await service.invoke(confirmation_request, "42")
        second = await service.invoke(confirmation_request, "42")
        self.assertEqual("completed", first.status)
        self.assertEqual("needs_more_info", second.status)
        self.assertEqual(
            1,
            len(
                [call for call in client.calls if call["tool_name"] == "create_booking"]
            ),
        )

    async def test_demo_e_conflict_after_confirmation_is_business_failure(self):
        conflict = {
            "success": False,
            "data": None,
            "error": {
                "code": "BOOKING_CONFLICT",
                "message": "The requested time overlaps an existing booking",
            },
        }
        service, _planner, client, _store, confirmation = await self.prepare_booking(
            conflict
        )
        response = await service.invoke(
            request(
                "yes",
                confirmation.shared_context,
                confirmed=True,
                confirmation_id=confirmation.confirmation_required.confirmation_id,
            ),
            "42",
        )
        self.assertEqual("completed", response.status)
        self.assertIsNone(response.error)
        self.assertEqual("BOOKING_CONFLICT", response.actions_taken[0].error_code)
        self.assertNotIn("last_booking_id", response.shared_context["facilities"])
        self.assertEqual(
            1,
            len(
                [call for call in client.calls if call["tool_name"] == "create_booking"]
            ),
        )

    async def test_demo_b_list_cancel_first_confirm(self):
        decisions = {
            "list": PlannerDecision(intent="list_user_bookings"),
            "cancel first": PlannerDecision(
                intent="cancel_booking",
                arguments={"reference": "first one"},
            ),
        }
        booking = {
            "bookingId": 55,
            "space": {"spaceId": 4, "name": "COM2 Project Room 03"},
            "startDateTime": "2026-08-10T14:00:00",
            "endDateTime": "2026-08-10T16:00:00",
            "status": "CONFIRMED",
        }
        fixtures = {
            "list_user_bookings": {"success": True, "data": [booking]},
            "get_booking_status": {"success": True, "data": booking},
            "cancel_booking": {
                "success": True,
                "data": {"bookingId": 55, "status": "CANCELLED"},
            },
        }
        service, planner, client, _store = self.build_service(decisions, fixtures)
        listed = await service.invoke(request("list"), "42")
        confirmation = await service.invoke(
            request("cancel first", listed.shared_context), "42"
        )
        self.assertEqual("needs_confirmation", confirmation.status)
        completed = await service.invoke(
            request(
                "yes",
                confirmation.shared_context,
                confirmed=True,
                confirmation_id=confirmation.confirmation_required.confirmation_id,
            ),
            "42",
        )
        self.assertEqual("completed", completed.status)
        self.assertIn("cancelled", completed.response)
        self.assertEqual(2, len(planner.calls))
        cancel_calls = [
            call for call in client.calls if call["tool_name"] == "cancel_booking"
        ]
        self.assertEqual({"bookingId": 55}, cancel_calls[0]["arguments"])

    async def test_cancel_that_booking_with_multiple_candidates_needs_more_info(self):
        decisions = {
            "list": PlannerDecision(intent="list_user_bookings"),
            "cancel that booking": PlannerDecision(
                intent="cancel_booking", arguments={"reference": "that booking"}
            ),
        }
        fixtures = {
            "list_user_bookings": {
                "success": True,
                "data": [
                    {"bookingId": 1, "status": "CONFIRMED"},
                    {"bookingId": 2, "status": "CONFIRMED"},
                ],
            }
        }
        service, _planner, client, _store = self.build_service(decisions, fixtures)
        listed = await service.invoke(request("list"), "42")
        response = await service.invoke(
            request("cancel that booking", listed.shared_context), "42"
        )
        self.assertEqual("needs_more_info", response.status)
        self.assertFalse(
            any(call["tool_name"] == "get_booking_status" for call in client.calls)
        )

    async def test_book_first_with_expired_context_needs_more_info(self):
        service, planner, client, _store, confirmation = await self.prepare_booking()
        expired_context = confirmation.shared_context
        expired_context["facilities"]["search_results"]["expiresAt"] = (
            self.clock.value - timedelta(seconds=1)
        ).isoformat()
        response = await service.invoke(request("book first", expired_context), "42")
        self.assertEqual("needs_more_info", response.status)
        self.assertEqual(3, len(planner.calls))
        self.assertEqual(
            1,
            len(
                [
                    call
                    for call in client.calls
                    if call["tool_name"] == "check_availability"
                ]
            ),
        )

    async def test_planner_user_id_is_never_forwarded(self):
        with self.assertRaises(ValueError):
            PlannerDecision(
                intent="search_spaces",
                arguments={"building": "COM2", "userId": 999, "user_id": 888},
            )

    async def test_demo_c_maintenance_clarify_confirm_submit(self):
        decisions = {
            "projector broken": PlannerDecision(
                intent="submit_maintenance_request",
                arguments={
                    "facilityType": "projector",
                    "description": "The projector is broken.",
                },
            ),
            "COM2-03": PlannerDecision(
                intent="submit_maintenance_request",
                arguments={"building": "COM2", "roomNumber": "03"},
            ),
        }
        fixtures = {
            "search_spaces": {
                "success": True,
                "data": [
                    {
                        "spaceId": 4,
                        "name": "COM2 Project Room 03",
                        "building": "COM2",
                        "roomNumber": "03",
                    }
                ],
            },
            "submit_maintenance_request": {
                "success": True,
                "data": {"ticketId": 456, "status": "SUBMITTED"},
            },
        }
        service, planner, client, _store = self.build_service(decisions, fixtures)
        clarification = await service.invoke(request("projector broken"), "42")
        self.assertEqual("needs_more_info", clarification.status)
        self.assertIn(
            "pending_maintenance_info", clarification.shared_context["facilities"]
        )
        confirmation = await service.invoke(
            request("COM2-03", clarification.shared_context), "42"
        )
        self.assertEqual("needs_confirmation", confirmation.status)
        completed = await service.invoke(
            request(
                "yes",
                confirmation.shared_context,
                confirmed=True,
                confirmation_id=confirmation.confirmation_required.confirmation_id,
            ),
            "42",
        )
        self.assertEqual("completed", completed.status)
        self.assertIn("456", completed.response)
        self.assertEqual(2, len(planner.calls))
        submit_call = [
            call
            for call in client.calls
            if call["tool_name"] == "submit_maintenance_request"
        ][0]
        self.assertEqual(
            {
                "spaceId": 4,
                "facilityType": "projector",
                "description": "The projector is broken.",
                "priority": "MEDIUM",
            },
            submit_call["arguments"],
        )
        self.assertNotIn(
            "pending_maintenance_info", completed.shared_context["facilities"]
        )

    async def test_demo_d_show_maintenance_requests(self):
        decisions = {
            "show maintenance": PlannerDecision(
                intent="list_user_maintenance_requests"
            )
        }
        fixtures = {
            "list_user_maintenance_requests": {
                "success": True,
                "data": [{"ticketId": 7, "status": "IN_PROGRESS"}],
            }
        }
        service, _planner, client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("show maintenance"), "42")
        self.assertEqual("completed", response.status)
        self.assertIn("ticket 7", response.response)
        self.assertEqual("list_user_maintenance_requests", client.calls[0]["tool_name"])
        self.assertEqual(
            7, response.shared_context["facilities"]["last_maintenance_ticket_id"]
        )

    async def test_direct_space_details_handler(self):
        decisions = {
            "details": PlannerDecision(
                intent="get_space_details", arguments={"spaceId": 3}
            )
        }
        fixtures = {
            "get_space_details": {
                "success": True,
                "data": {"spaceId": 3, "name": "COM2 Room 3"},
            }
        }
        service, _planner, client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("details"), "42")
        self.assertEqual("completed", response.status)
        self.assertEqual(
            {"spaceId": 3},
            client.calls[0]["arguments"],
        )

    async def test_direct_availability_uses_deterministic_chinese_time(self):
        decisions = {
            "available": PlannerDecision(
                intent="check_availability",
                arguments={"spaceId": 3},
                datetime_text="明天下午2点到4点",
            )
        }
        fixtures = {
            "check_availability": {
                "success": True,
                "data": {"available": True},
            }
        }
        service, _planner, client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("available"), "42")
        self.assertEqual("completed", response.status)
        self.assertEqual(
            {
                "spaceId": 3,
                "startDateTime": "2026-08-10T14:00:00",
                "endDateTime": "2026-08-10T16:00:00",
            },
            client.calls[0]["arguments"],
        )

    async def test_ambiguous_availability_does_not_call_backend(self):
        decisions = {
            "ambiguous": PlannerDecision(
                intent="check_availability",
                arguments={"spaceId": 3},
                datetime_text="tomorrow at 2",
            )
        }
        service, _planner, client, _store = self.build_service(decisions, {})
        response = await service.invoke(request("ambiguous"), "42")
        self.assertEqual("needs_more_info", response.status)
        self.assertEqual([], client.calls)

    async def test_direct_booking_status_handler(self):
        decisions = {
            "status": PlannerDecision(
                intent="get_booking_status", arguments={"bookingId": 123}
            )
        }
        fixtures = {
            "get_booking_status": {
                "success": True,
                "data": {"bookingId": 123, "status": "CONFIRMED"},
            }
        }
        service, _planner, client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("status"), "42")
        self.assertEqual("completed", response.status)
        self.assertIn("CONFIRMED", response.response)
        self.assertEqual("get_booking_status", client.calls[0]["tool_name"])

    async def test_direct_maintenance_status_handler(self):
        decisions = {
            "ticket": PlannerDecision(
                intent="get_maintenance_status", arguments={"ticketId": 123}
            )
        }
        fixtures = {
            "get_maintenance_status": {
                "success": True,
                "data": {"ticketId": 123, "status": "IN_PROGRESS"},
            }
        }
        service, _planner, client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("ticket"), "42")
        self.assertEqual("completed", response.status)
        self.assertIn("IN_PROGRESS", response.response)
        self.assertEqual("get_maintenance_status", client.calls[0]["tool_name"])

    async def test_missing_fields_uses_planner_clarification_without_tool_call(self):
        decisions = {
            "missing": PlannerDecision(
                intent="submit_maintenance_request",
                missing_fields=["roomNumber"],
                clarification="Which room is affected?",
            )
        }
        service, _planner, client, _store = self.build_service(decisions, {})
        response = await service.invoke(request("missing"), "42")
        self.assertEqual("needs_more_info", response.status)
        self.assertEqual("Which room is affected?", response.response)
        self.assertEqual([], client.calls)

    async def test_internal_error_is_system_failure(self):
        decisions = {"search": PlannerDecision(intent="search_spaces")}
        fixtures = {
            "search_spaces": {
                "success": False,
                "error": {
                    "code": "INTERNAL_ERROR",
                    "message": "sensitive backend detail",
                },
            }
        }
        service, _planner, _client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("search"), "42")
        self.assertEqual("failed", response.status)
        self.assertEqual("FACILITIES_BACKEND_ERROR", response.error)
        self.assertNotIn("sensitive backend detail", response.response)

    async def test_unknown_backend_error_is_system_failure(self):
        decisions = {"search": PlannerDecision(intent="search_spaces")}
        fixtures = {
            "search_spaces": {
                "success": False,
                "error": {"code": "UNEXPECTED_DATABASE_FAILURE", "message": "db"},
            }
        }
        service, _planner, _client, _store = self.build_service(decisions, fixtures)
        response = await service.invoke(request("search"), "42")
        self.assertEqual("failed", response.status)
        self.assertEqual("FACILITIES_BACKEND_ERROR", response.error)

    async def test_canonical_intents_cover_exact_ten_spring_tools(self):
        self.assertEqual(
            FACILITIES_TOOL_NAMES,
            FACILITIES_INTENTS - {"unsupported"},
        )

    async def test_fake_client_supports_exactly_ten_contract_tools(self):
        self.assertEqual(10, len(FACILITIES_TOOL_NAMES))
        client = FakeFacilitiesToolClient()
        for tool_name in FACILITIES_TOOL_NAMES:
            response = await client.call_tool(tool_name, {})
            self.assertTrue(response["success"])


if __name__ == "__main__":
    unittest.main()
