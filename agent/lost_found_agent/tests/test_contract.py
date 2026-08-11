import json
from pathlib import Path

from jsonschema import Draft202012Validator


def test_agent_schema_is_valid_and_requires_confirmation() -> None:
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    Draft202012Validator.check_schema(schema)
    expected_actions = [
        "report_lost",
        "report_found",
        "search_found_items",
        "search_lost_items",
        "get_item_detail",
        "claim_item",
    ]

    assert schema["version"] == "1.5.0"
    assert schema["security"]["writeConfirmationRequired"] == [
        "report_lost",
        "report_found",
        "claim_item",
    ]
    assert schema["capabilities"]["actions"] == expected_actions
    assert [tool["name"] for tool in schema["internalTools"]] == expected_actions
    assert schema["model"]["allowedTools"] == expected_actions
    assert schema["capabilities"]["privacy"]["exposesPublisherContact"] is False
    assert schema["matching"]["topK"] == 5
    assert sum(schema["matching"]["weights"].values()) == 1
    assert schema["model"]["maximumToolsPerInvocation"] == 2
    assert schema["model"]["fallbackMode"] == "rules"


def test_sample_response_matches_contract() -> None:
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator({"$ref": "#/$defs/invokeOutput", "$defs": root["$defs"]})
    sample: dict[str, object] = {
        "response": "需要补充物品描述。",
        "status": "needs_more_info",
        "match_results": [],
        "confirmation_required": None,
        "shared_context": {},
        "actions_taken": [],
        "request_id": "request-1",
    }

    validator.validate(sample)


def test_report_found_confirmation_matches_contract() -> None:
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator({"$ref": "#/$defs/invokeOutput", "$defs": root["$defs"]})
    sample: dict[str, object] = {
        "response": "请确认登记这件拾获物品。",
        "status": "needs_confirmation",
        "match_results": [],
        "confirmation_required": {
            "confirmation_id": "confirmation-1",
            "action": "report_found",
            "summary": "黑色耳机，中央图书馆，2026-08-08",
            "expires_at": "2026-08-11T10:10:00Z",
        },
        "shared_context": {},
        "actions_taken": [],
        "request_id": "request-report-found",
    }

    validator.validate(sample)
