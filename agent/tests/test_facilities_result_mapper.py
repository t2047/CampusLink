import unittest

from agent.facilities_agent.result_mapper import map_technical_error, map_tool_result
from agent.facilities_agent.tool_client import ToolClientTransportError


class ResultMapperTest(unittest.TestCase):
    def test_booking_conflict_is_business_result_not_mcp_unavailable(self):
        response = map_tool_result(
            "create_booking",
            {
                "success": False,
                "data": None,
                "error": {
                    "code": "BOOKING_CONFLICT",
                    "message": "Overlapping booking",
                },
            },
            {"facilities": {"version": 1}},
            "req-1",
        )
        self.assertEqual("completed", response.status)
        self.assertIsNone(response.error)
        self.assertEqual("BOOKING_CONFLICT", response.actions_taken[0].error_code)
        self.assertNotIn("unavailable", str(response.error).lower())

    def test_validation_error_requests_more_information(self):
        response = map_tool_result(
            "create_booking",
            {
                "success": False,
                "error": {"code": "VALIDATION_ERROR", "message": "Missing end"},
            },
            {},
            "req-2",
        )
        self.assertEqual("needs_more_info", response.status)
        self.assertIsNone(response.error)

    def test_transport_error_is_top_level_system_failure(self):
        response = map_technical_error(ToolClientTransportError("down"), {}, "req-3")
        self.assertEqual("failed", response.status)
        self.assertEqual("FACILITIES_MCP_UNAVAILABLE", response.error)

    def test_authentication_envelope_is_top_level_system_failure(self):
        response = map_tool_result(
            "list_user_bookings",
            {
                "success": False,
                "error": {
                    "code": "AUTHENTICATION_REQUIRED",
                    "message": "Missing security context",
                },
            },
            {},
            "req-4",
        )
        self.assertEqual("failed", response.status)
        self.assertEqual("FACILITIES_AUTHENTICATION_FAILED", response.error)


if __name__ == "__main__":
    unittest.main()
