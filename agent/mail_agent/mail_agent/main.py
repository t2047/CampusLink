from __future__ import annotations

from datetime import datetime, timezone
from enum import StrEnum
from uuid import uuid4

from fastapi import FastAPI, Header, HTTPException, Query, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field, field_validator


class MailFolder(StrEnum):
    inbox = "inbox"
    sent = "sent"
    archived = "archived"
    trash = "trash"


class MailMessage(BaseModel):
    model_config = ConfigDict(use_enum_values=True)

    id: str
    subject: str
    sender: str
    recipients: list[str]
    preview: str
    body: str
    folder: MailFolder
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


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _preview(body: str) -> str:
    compact = " ".join(body.split())
    return compact[:140]


def _user_from_auth(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing Authorization")
    if not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Authorization")
    return "student@campuslink.local"


def _seed_messages(owner: str) -> list[MailMessage]:
    now = _now()
    return [
        MailMessage(
            id="mail-1",
            subject="Exam arrangements for CS2103",
            sender="cs-office@campus.edu",
            recipients=[owner],
            preview="The final exam venue has been confirmed. Please bring your student card.",
            body=(
                "The final exam venue has been confirmed.\n\n"
                "Date: 2026-08-20\n"
                "Venue: LT19\n"
                "Please bring your student card and arrive 20 minutes early."
            ),
            folder=MailFolder.inbox,
            read=False,
            starred=True,
            created_at=now,
            updated_at=now,
        ),
        MailMessage(
            id="mail-2",
            subject="Library reminder",
            sender="library@campus.edu",
            recipients=[owner],
            preview="One borrowed book is due soon. Renew it online if you need more time.",
            body="One borrowed book is due soon. Renew it online if you need more time.",
            folder=MailFolder.inbox,
            read=True,
            starred=False,
            created_at=now,
            updated_at=now,
        ),
        MailMessage(
            id="mail-3",
            subject="Project meeting notes",
            sender=owner,
            recipients=["teammate@campus.edu"],
            preview="Here are the meeting notes and the next actions for our AD project.",
            body="Here are the meeting notes and the next actions for our AD project.",
            folder=MailFolder.sent,
            read=True,
            starred=False,
            created_at=now,
            updated_at=now,
        ),
    ]


app = FastAPI(title="CampusLink Mail Service", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:8080"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

_mailboxes: dict[str, list[MailMessage]] = {}


def _mailbox(owner: str) -> list[MailMessage]:
    if owner not in _mailboxes:
        _mailboxes[owner] = _seed_messages(owner)
    return _mailboxes[owner]


def _require_message(owner: str, message_id: str) -> MailMessage:
    for message in _mailbox(owner):
        if message.id == message_id:
            return message
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Message not found")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "mail-agent"}


@app.get("/api/mail/messages", response_model=PageResponse)
async def list_messages(
    authorization: str | None = Header(default=None),
    folder: MailFolder = MailFolder.inbox,
    q: str = "",
    unread: bool | None = None,
    starred: bool | None = None,
    page: int = Query(default=0, ge=0),
    size: int = Query(default=20, ge=1, le=100),
) -> PageResponse:
    owner = _user_from_auth(authorization)
    query = q.strip().lower()
    messages = [message for message in _mailbox(owner) if message.folder == folder]
    if query:
        messages = [
            message
            for message in messages
            if query in message.subject.lower()
            or query in message.sender.lower()
            or query in message.body.lower()
        ]
    if unread is not None:
        messages = [message for message in messages if message.read is not unread]
    if starred is not None:
        messages = [message for message in messages if message.starred is starred]

    messages.sort(key=lambda item: item.created_at, reverse=True)
    total = len(messages)
    start = page * size
    end = start + size
    total_pages = (total + size - 1) // size if total else 0
    return PageResponse(
        content=messages[start:end],
        page=page,
        size=size,
        total_elements=total,
        total_pages=total_pages,
        first=page == 0,
        last=page >= max(total_pages - 1, 0),
    )


@app.get("/api/mail/messages/{message_id}", response_model=MailMessage)
async def get_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    owner = _user_from_auth(authorization)
    message = _require_message(owner, message_id)
    if not message.read:
        message.read = True
        message.updated_at = _now()
    return message


@app.post("/api/mail/messages", response_model=MailMessage, status_code=status.HTTP_201_CREATED)
async def send_message(
    request: SendMailRequest,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    owner = _user_from_auth(authorization)
    now = _now()
    message = MailMessage(
        id=f"mail-{uuid4().hex[:12]}",
        subject=request.subject.strip(),
        sender=owner,
        recipients=request.recipients,
        preview=_preview(request.body),
        body=request.body.strip(),
        folder=MailFolder.sent,
        read=True,
        starred=False,
        created_at=now,
        updated_at=now,
    )
    _mailbox(owner).append(message)
    return message


@app.patch("/api/mail/messages/{message_id}", response_model=MailMessage)
async def update_message(
    message_id: str,
    request: UpdateMailRequest,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    owner = _user_from_auth(authorization)
    message = _require_message(owner, message_id)
    if request.read is not None:
        message.read = request.read
    if request.starred is not None:
        message.starred = request.starred
    if request.folder is not None:
        message.folder = request.folder
    message.updated_at = _now()
    return message


@app.post("/api/mail/messages/{message_id}/archive", response_model=MailMessage)
async def archive_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    owner = _user_from_auth(authorization)
    message = _require_message(owner, message_id)
    message.folder = MailFolder.archived
    message.updated_at = _now()
    return message


@app.post("/api/mail/messages/{message_id}/delete", response_model=MailMessage)
async def delete_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    owner = _user_from_auth(authorization)
    message = _require_message(owner, message_id)
    message.folder = MailFolder.trash
    message.updated_at = _now()
    return message
