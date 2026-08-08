import json
from pathlib import Path

from jsonschema import Draft202012Validator


def test_agent_schema_is_valid_and_requires_confirmation() -> None:
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    Draft202012Validator.check_schema(schema)
    assert schema["version"] == "1.2.0"
    assert schema["security"]["writeConfirmationRequired"] == [
        "report_lost",
        "claim_item",
    ]
    assert schema["capabilities"]["privacy"]["exposesPublisherContact"] is False
    assert schema["matching"]["topK"] == 5
    assert sum(schema["matching"]["weights"].values()) == 1


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
