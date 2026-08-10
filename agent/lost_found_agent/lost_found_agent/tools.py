"""调用 Spring Boot 内部 API 的真实 Lost & Found 工具。"""

from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal
from uuid import uuid4

import httpx
import jwt
from pydantic import BaseModel, Field, field_validator, model_validator

from .config import Settings

ItemCategory = Literal[
    "ELECTRONICS",
    "ID_CARD",
    "WALLET_PURSE",
    "KEYS",
    "BAG",
    "CLOTHING",
    "BOOKS_STATIONERY",
    "UMBRELLA",
    "OTHER",
]


class ReportLostInput(BaseModel):
    item_name: str = Field(min_length=3, max_length=100)
    category: ItemCategory
    description: str = Field(min_length=10, max_length=2000)
    location: str = Field(min_length=1, max_length=200)
    event_date: date
    colour: str | None = Field(default=None, max_length=50)
    time_description: str | None = Field(default=None, max_length=100)

    @field_validator("event_date")
    @classmethod
    def event_date_cannot_be_future(cls, value: date) -> date:
        if value > date.today():
            raise ValueError("event_date cannot be in the future")
        return value


class ReportFoundInput(BaseModel):
    """捡到（FOUND）报告：字段与报失对称（捡到物品登记）。"""

    item_name: str = Field(min_length=3, max_length=100)
    category: ItemCategory
    description: str = Field(min_length=10, max_length=2000)
    location: str = Field(min_length=1, max_length=200)
    event_date: date
    colour: str | None = Field(default=None, max_length=50)
    time_description: str | None = Field(default=None, max_length=100)

    @field_validator("event_date")
    @classmethod
    def event_date_cannot_be_future(cls, value: date) -> date:
        if value > date.today():
            raise ValueError("event_date cannot be in the future")
        return value


class SearchFoundItemsInput(BaseModel):
    keyword: str | None = None
    category: ItemCategory | None = None
    colour: str | None = None
    location: str | None = None
    date_from: date | None = None
    date_to: date | None = None
    page: int = Field(default=0, ge=0)
    size: int = Field(default=100, ge=1, le=100)

    @model_validator(mode="after")
    def validate_date_range(self) -> "SearchFoundItemsInput":
        if self.date_from and self.date_to and self.date_from > self.date_to:
            raise ValueError("date_from must be on or before date_to")
        return self


class GetItemDetailInput(BaseModel):
    report_id: int = Field(gt=0)


class ClaimItemInput(BaseModel):
    report_id: int = Field(gt=0)
    proof_description: str = Field(min_length=10, max_length=1000)


class BackendApiError(RuntimeError):
    """已脱敏的后端错误，不包含令牌或底层响应正文。"""

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code


class CampusApiClient:
    """每次工具调用都签发独立、短期、一次性的 Delegation Token。"""

    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.campus_api_url.rstrip("/"),
            timeout=httpx.Timeout(10.0),
            transport=transport,
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def report_lost(
        self, user_id: str, user_role: str, payload: ReportLostInput
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/api/internal/lost-found/reports/lost",
            "report_lost",
            user_id,
            user_role,
            json={
                "itemName": payload.item_name,
                "category": payload.category,
                "description": payload.description,
                "colour": payload.colour,
                "location": payload.location,
                "eventDate": payload.event_date.isoformat(),
                "timeDescription": payload.time_description,
            },
        )

    async def report_found(
        self, user_id: str, user_role: str, payload: ReportFoundInput
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/api/internal/lost-found/reports/found",
            "report_found",
            user_id,
            user_role,
            json={
                "itemName": payload.item_name,
                "category": payload.category,
                "description": payload.description,
                "colour": payload.colour,
                "location": payload.location,
                "eventDate": payload.event_date.isoformat(),
                "timeDescription": payload.time_description,
            },
        )

    async def search_found_items(
        self, user_id: str, user_role: str, payload: SearchFoundItemsInput
    ) -> dict[str, Any]:
        params: dict[str, str | int] = {"page": payload.page, "size": payload.size}
        optional: dict[str, str | None] = {
            "keyword": payload.keyword,
            "category": payload.category,
            "colour": payload.colour,
            "location": payload.location,
            "dateFrom": payload.date_from.isoformat() if payload.date_from else None,
            "dateTo": payload.date_to.isoformat() if payload.date_to else None,
        }
        params.update({key: value for key, value in optional.items() if value is not None})
        return await self._request(
            "GET",
            "/api/internal/lost-found/candidates",
            "search_found_items",
            user_id,
            user_role,
            params=params,
        )

    async def get_item_detail(
        self, user_id: str, user_role: str, payload: GetItemDetailInput
    ) -> dict[str, Any]:
        return await self._request(
            "GET",
            f"/api/internal/lost-found/reports/{payload.report_id}",
            "get_item_detail",
            user_id,
            user_role,
        )

    async def claim_item(
        self, user_id: str, user_role: str, payload: ClaimItemInput
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/internal/lost-found/reports/{payload.report_id}/claims",
            "claim_item",
            user_id,
            user_role,
            json={"proofDescription": payload.proof_description},
        )

    async def _request(
        self,
        method: str,
        path: str,
        action: str,
        user_id: str,
        user_role: str,
        **kwargs: Any,
    ) -> dict[str, Any]:
        token = self._delegation_token(user_id, user_role, action)
        try:
            response = await self._client.request(
                method, path, headers={"Authorization": f"Bearer {token}"}, **kwargs
            )
        except httpx.TimeoutException as exc:
            raise BackendApiError(504, "CAMPUS_API_TIMEOUT", "Campus API 请求超时") from exc
        except httpx.HTTPError as exc:
            raise BackendApiError(503, "CAMPUS_API_UNAVAILABLE", "Campus API 暂时不可用") from exc

        if response.is_error:
            try:
                error = response.json()
            except ValueError:
                error = {}
            code = str(error.get("code") or f"CAMPUS_API_{response.status_code}")
            message = str(error.get("message") or error.get("error") or "Campus API 拒绝了该操作")
            raise BackendApiError(response.status_code, code, message)
        data = response.json()
        if not isinstance(data, dict):
            raise BackendApiError(502, "INVALID_CAMPUS_API_RESPONSE", "Campus API 响应格式无效")
        return data

    def _delegation_token(self, user_id: str, user_role: str, action: str) -> str:
        now = datetime.now(UTC)
        return jwt.encode(
            {
                "aud": "campus-api",
                "iss": "lost-found-agent",
                "sub": user_id,
                "role": user_role,
                "iat": now,
                "exp": now + timedelta(seconds=30),
                "jti": str(uuid4()),
                "intended_action": action,
            },
            self._settings.agent_backend_shared_secret,
            algorithm="HS256",
        )
