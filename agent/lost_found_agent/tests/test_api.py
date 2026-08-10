from fastapi.testclient import TestClient

from lost_found_agent.config import Settings

from .helpers import signed_request


def test_public_health_reports_rules_mode(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "lost-found-agent",
        "version": "0.6.0",
        "mode": "rules",
        "model_configured": False,
    }


def test_public_capabilities_do_not_expose_secrets(client: TestClient) -> None:
    response = client.get("/agent/capabilities")

    assert response.status_code == 200
    data = response.json()
    assert data["capabilities"]["write_confirmation_required"] is True
    assert data["capabilities"]["actions"] == [
        "report_lost",
        "search_found_items",
        "get_item_detail",
        "claim_item",
    ]
    assert "secret" not in response.text.lower()


def test_invoke_requires_security_headers(client: TestClient) -> None:
    response = client.post("/agent/invoke", json={"message": "找雨伞"})

    assert response.status_code == 401


def test_authenticated_invoke_creates_replayable_events(
    client: TestClient, settings: Settings
) -> None:
    payload = {
        "message": "找雨伞",
        "conversation_context": {"session_id": "session-1", "shared_data": {}},
        "trace_parent": {"trace_id": "request-1"},
    }
    body, headers = signed_request(settings, payload)

    response = client.post("/agent/invoke", content=body, headers=headers)

    assert response.status_code == 200
    assert response.json()["status"] in {"no_match", "needs_more_info"}
    assert response.json()["request_id"] == "request-1"

    stream_body, stream_headers = signed_request(settings, None, action="stream")
    stream = client.request(
        "GET",
        "/agent/stream",
        params={"request_id": "request-1"},
        content=stream_body,
        headers=stream_headers,
    )
    assert stream.status_code == 200
    assert "event: agent_start" in stream.text
    assert "event: agent_done" in stream.text


def test_nonce_cannot_be_reused(client: TestClient, settings: Settings) -> None:
    payload = {"message": "find my umbrella"}
    body, headers = signed_request(settings, payload, nonce="one-time-nonce")

    assert client.post("/agent/invoke", content=body, headers=headers).status_code == 200
    replay = client.post("/agent/invoke", content=body, headers=headers)

    assert replay.status_code == 401
    assert replay.json()["detail"] == "Nonce 已被使用"


def test_token_action_is_scoped(client: TestClient, settings: Settings) -> None:
    payload = {"message": "find my umbrella"}
    body, headers = signed_request(settings, payload, action="stream")

    response = client.post("/agent/invoke", content=body, headers=headers)

    assert response.status_code == 403
