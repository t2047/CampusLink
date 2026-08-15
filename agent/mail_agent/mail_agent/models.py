"""Shared request/response models for the CampusLink mail service.

The shapes mirror the frontend contract (``frontend_web/src/types.ts``) and the
in-memory mock they replaced, so the web UI and the MCP adapter keep working
unchanged.
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MailFolder(StrEnum):
    inbox = "inbox"
    sent = "sent"
    archived = "archived"
    trash = "trash"
    spam = "spam"


class MailCategory(StrEnum):
    campus = "campus"
    career = "career"
    finance = "finance"
    other = "other"


class MailMessage(BaseModel):
    model_config = ConfigDict(use_enum_values=True)

    id: str
    subject: str
    sender: str
    recipients: list[str]
    preview: str
    body: str
    body_html: str | None = None
    folder: MailFolder
    category: MailCategory = MailCategory.other
    read: bool = False
    starred: bool = False
    created_at: datetime
    updated_at: datetime


class PageResponse(BaseModel):
    content: list[MailMessage]
    page: int
    size: int
    total_elements: int
    total_pages: int
    first: bool
    last: bool


class SendMailRequest(BaseModel):
    recipients: list[str] = Field(min_length=1, max_length=20)
    subject: str = Field(min_length=1, max_length=160)
    body: str = Field(min_length=1, max_length=10000)

    @field_validator("recipients")
    @classmethod
    def validate_recipients(cls, value: list[str]) -> list[str]:
        cleaned = [recipient.strip() for recipient in value if recipient.strip()]
        if not cleaned:
            raise ValueError("At least one recipient is required")
        invalid = [recipient for recipient in cleaned if "@" not in recipient]
        if invalid:
            raise ValueError("Recipients must look like email addresses")
        return cleaned


class UpdateMailRequest(BaseModel):
    read: bool | None = None
    starred: bool | None = None
    folder: MailFolder | None = None


class OAuthUrlResponse(BaseModel):
    auth_url: str
    connected: bool


class OAuthStatusResponse(BaseModel):
    connected: bool
    email: str | None = None


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def preview_of(body: str) -> str:
    return " ".join(body.split())[:140]
