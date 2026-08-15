"""调用 Spring Boot 内部 API 的真实 Lost & Found 工具。"""

from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal
from urllib.parse import quote
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
    # 中文物品名常为 2 字符，min_length=2 与 llm.py 提取口径一致
    item_name: str = Field(min_length=2, max_length=100)
    category: ItemCategory
    description: str = Field(min_length=10, max_length=2000)
    location: str = Field(min_length=1, max_length=200)
    event_date: date
    colour: str | None = Field(default=None, max_length=50)
    time_description: str | None = Field(default=None, max_length=100)
    # 面板已暂存图片的 objectKey；确认创建时经内部 API 关联为报告图片。
    # 字段本身不发给后端（body 构建器只挑字段），仅用于确认载荷与自动匹配 query。
    images: list[str] = Field(default_factory=list, max_length=5)
    # 查询端视觉指纹（与 images 同序），创建后并入自动匹配 query 参与打分。
    visual_fingerprints: list[str] = Field(default_factory=list, max_length=5)
    visual_embeddings: list[str] = Field(default_factory=list, max_length=5)

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
    images: list[str] = Field(default_factory=list, max_length=5)
    visual_fingerprints: list[str] = Field(default_factory=list, max_length=5)
    visual_embeddings: list[str] = Field(default_factory=list, max_length=5)

    @field_validator("event_date")
    @classmethod
    def event_date_cannot_be_future(cls, value: date) -> date:
        if value > date.today():
            raise ValueError("event_date cannot be in the future")
        return value


class SearchItemsInput(BaseModel):
    keyword: str | None = None
    category: ItemCategory | None = None
    colour: str | None = None
    location: str | None = None
    date_from: date | None = None
    date_to: date | None = None
    page: int = Field(default=0, ge=0)
    size: int = Field(default=100, ge=1, le=100)

    @model_validator(mode="after")
    def validate_date_range(self) -> "SearchItemsInput":
        if self.date_from and self.date_to and self.date_from > self.date_to:
            raise ValueError("date_from must be on or before date_to")
        return self


class SearchFoundItemsInput(SearchItemsInput):
    """搜索开放的拾获记录。"""


class SearchLostItemsInput(SearchItemsInput):
    """搜索开放的报失记录。"""


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
                **({"imageKeys": payload.images} if payload.images else {}),
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
                **({"imageKeys": payload.images} if payload.images else {}),
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

    async def search_lost_items(
        self, user_id: str, user_role: str, payload: SearchLostItemsInput
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
            "/api/internal/lost-found/lost-candidates",
            "search_lost_items",
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

    # ─────────────────────────── 记忆内部 API ───────────────────────────
    # chat-memory-requirements §6：agent 无 DB 连接，经这些端点读写 MySQL 记忆。
    # sessionId 作为路径段，须 URL 编码（uuid 等安全字符不变，兼容任意合法值）。

    async def memory_upsert_session(
        self,
        user_id: str,
        user_role: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/api/internal/lost-found/memory/sessions",
            "memory_upsert_session",
            user_id,
            user_role,
            json=payload,
        )

    async def memory_get_session(
        self,
        user_id: str,
        user_role: str,
        session_id: str,
    ) -> dict[str, Any]:
        return await self._request(
            "GET",
            f"/api/internal/lost-found/memory/sessions/{quote(session_id, safe='')}",
            "memory_read",
            user_id,
            user_role,
        )

    async def memory_append_message(
        self,
        user_id: str,
        user_role: str,
        session_id: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/internal/lost-found/memory/sessions/{quote(session_id, safe='')}/messages",
            "memory_append",
            user_id,
            user_role,
            json=payload,
        )

    async def memory_prune_messages(
        self,
        user_id: str,
        user_role: str,
        session_id: str,
        keep_latest: int,
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/internal/lost-found/memory/sessions/{quote(session_id, safe='')}/messages/prune",
            "memory_prune_messages",
            user_id,
            user_role,
            json={"keepLatest": keep_latest},
        )

    async def memory_get_user_facts(
        self,
        user_id: str,
        user_role: str,
    ) -> dict[str, Any]:
        return await self._request(
            "GET",
            "/api/internal/lost-found/memory/users/me",
            "memory_read",
            user_id,
            user_role,
        )

    async def memory_upsert_fact(
        self,
        user_id: str,
        user_role: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/api/internal/lost-found/memory/users/me/facts",
            "memory_upsert_fact",
            user_id,
            user_role,
            json=payload,
        )

    async def memory_delete_user(
        self,
        user_id: str,
        user_role: str,
    ) -> dict[str, Any]:
        return await self._request(
            "DELETE",
            "/api/internal/lost-found/memory/users/me",
            "memory_delete",
            user_id,
            user_role,
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
