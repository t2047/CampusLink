from datetime import date
from typing import Any

import httpx
import jwt
import pytest

from lost_found_agent.config import Settings
from lost_found_agent.tools import (
    BackendApiError,
    CampusApiClient,
    ClaimItemInput,
    GetItemDetailInput,
    ReportLostInput,
    SearchFoundItemsInput,
)


@pytest.mark.parametrize(
    ("method", "path", "action"),
    [
        ("POST", "/api/internal/lost-found/reports/lost", "report_lost"),
        ("GET", "/api/internal/lost-found/candidates", "search_found_items"),
        ("GET", "/api/internal/lost-found/reports/7", "get_item_detail"),
        ("POST", "/api/internal/lost-found/reports/7/claims", "claim_item"),
    ],
)
async def test_each_tool_uses_expected_route_and_scoped_token(
    settings: Settings, method: str, path: str, action: str
) -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"ok": True})

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        if action == "report_lost":
            await client.report_lost(
                "42",
                "STUDENT",
                ReportLostInput(
                    item_name="Black headphones",
                    category="ELECTRONICS",
                    description="Black wireless headphones in a fabric case",
                    location="Central Library",
                    event_date=date(2026, 8, 8),
                ),
            )
        elif action == "search_found_items":
            await client.search_found_items(
                "42",
                "STUDENT",
                SearchFoundItemsInput(category="ELECTRONICS", location="Library"),
            )
        elif action == "get_item_detail":
            await client.get_item_detail("42", "STUDENT", GetItemDetailInput(report_id=7))
        else:
            await client.claim_item(
                "42",
                "STUDENT",
                ClaimItemInput(report_id=7, proof_description="A unique scratch is under the case"),
            )
    finally:
        await client.close()

    request = captured[0]
    assert request.method == method
    assert request.url.path == path
    token = request.headers["Authorization"].removeprefix("Bearer ")
    claims: dict[str, Any] = jwt.decode(
        token,
        settings.agent_backend_shared_secret,
        algorithms=["HS256"],
        audience="campus-api",
        issuer="lost-found-agent",
    )
    assert claims["sub"] == "42"
    assert claims["role"] == "STUDENT"
    assert claims["intended_action"] == action
    assert claims["exp"] - claims["iat"] <= 60
    assert claims["jti"]


async def test_search_maps_filters_to_query_parameters(settings: Settings) -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"content": []})

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        await client.search_found_items(
            "8",
            "STUDENT",
            SearchFoundItemsInput(
                keyword="headphones",
                category="ELECTRONICS",
                colour="black",
                location="library",
                date_from=date(2026, 8, 1),
                date_to=date(2026, 8, 8),
            ),
        )
    finally:
        await client.close()

    params = captured[0].url.params
    assert params["category"] == "ELECTRONICS"
    assert params["dateFrom"] == "2026-08-01"
    assert params["dateTo"] == "2026-08-08"
    assert params["size"] == "100"


async def test_backend_domain_error_is_mapped_without_exposing_token(settings: Settings) -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            409,
            json={"code": "CLAIM_ALREADY_EXISTS", "message": "Already submitted"},
        )

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        with pytest.raises(BackendApiError) as caught:
            await client.claim_item(
                "42",
                "STUDENT",
                ClaimItemInput(report_id=7, proof_description="A unique scratch is under the case"),
            )
    finally:
        await client.close()

    assert caught.value.status_code == 409
    assert caught.value.code == "CLAIM_ALREADY_EXISTS"
    assert str(caught.value) == "Already submitted"
    assert "Bearer" not in str(caught.value)


async def test_backend_error_field_is_preserved_for_safe_user_feedback(settings: Settings) -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            409,
            json={
                "code": "CLAIM_ALREADY_EXISTS",
                "error": "You already have an active claim for this item",
            },
        )

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        with pytest.raises(BackendApiError) as caught:
            await client.claim_item(
                "42",
                "STUDENT",
                ClaimItemInput(report_id=7, proof_description="A unique scratch is under the case"),
            )
    finally:
        await client.close()

    assert caught.value.code == "CLAIM_ALREADY_EXISTS"
    assert str(caught.value) == "You already have an active claim for this item"


async def test_network_failure_is_mapped_to_service_unavailable(settings: Settings) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection failed", request=request)

    client = CampusApiClient(settings, httpx.MockTransport(handler))
    try:
        with pytest.raises(BackendApiError) as caught:
            await client.get_item_detail("42", "STUDENT", GetItemDetailInput(report_id=9))
    finally:
        await client.close()

    assert caught.value.status_code == 503
    assert caught.value.code == "CAMPUS_API_UNAVAILABLE"
