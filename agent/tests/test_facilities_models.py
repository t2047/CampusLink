import unittest

from pydantic import ValidationError

from agent.facilities_agent.models import InvokeRequest, InvokeResponse


class FacilitiesModelsTest(unittest.TestCase):
    def test_invoke_request_uses_chat_core_field_names(self):
        request = InvokeRequest.model_validate(
            {
                "message": "Find a room",
                "conversation_context": {
                    "session_id": "session-1",
                    "shared_data": {},
                },
                "confirmed": False,
                "confirmation_id": None,
                "trace_parent": {
                    "trace_id": "trace-1",
                    "parent_span_id": "span-1",
                },
            }
        )
        self.assertEqual("Find a room", request.message)
        self.assertEqual("session-1", request.conversation_context.session_id)

    def test_identity_is_not_an_invoke_argument(self):
        with self.assertRaises(ValidationError):
            InvokeRequest.model_validate({"message": "Find a room", "user_id": "42"})

    def test_output_status_is_bounded(self):
        with self.assertRaises(ValidationError):
            InvokeResponse(
                response="bad",
                status="partial",
                shared_context={},
                actions_taken=[],
                request_id="req-1",
            )


if __name__ == "__main__":
    unittest.main()
