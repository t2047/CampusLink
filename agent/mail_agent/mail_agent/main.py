"""CampusLink Mail Service - Gmail-backed, per-user Gmail authorization.

Exposes ``/api/mail/**`` used by the web frontend (and the MCP mail gateway),
backed by the real Gmail API via OAuth2. The shapes match the previous in-memory
mock so existing clients keep working.

**Per-user Gmail binding**: each CampusLink user authorises their own Gmail
account. The caller identity is resolved from the ``Authorization`` header
(``auth.resolve_identity``: user JWT ``sub`` = email for the web path, or an
internal MCP-gateway token for the chat path) and every mail/calendar
operation runs against that user's own credentials.

OAuth flow:
  * ``GET  /api/mail/oauth/url``     -> Google consent URL bound to the user.
  * ``GET  /api/mail/oauth/status``  -> {connected, email} for the user.
  * ``GET  /callback``               -> Google redirect target; exchanges the
                                       code for a token and stores it for the
                                       user recovered from ``state``.
  * ``POST /api/mail/oauth/disconnect`` -> remove only *this* user's token.
"""

from __future__ import annotations

import contextvars
import math
import re
import uuid

from fastapi import FastAPI, Header, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from pydantic import BaseModel, Field

from . import agent, auth, calendar_service, config, gmail_service
from .calendar_service import (
    CalendarEvent,
    CalendarEventRequest,
    CalendarEventUpdate,
    ExtractedSchedule,
    ImportRequest,
    ImportResponse,
)
from .models import (
    MailFolder,
    MailMessage,
    OAuthStatusResponse,
    OAuthUrlResponse,
    PageResponse,
    SendMailRequest,
    UpdateMailRequest,
)


class MailApiError(Exception):
    """Domain error rendered as a flat ``{code, error, ...}`` JSON body."""

    def __init__(self, status_code: int, code: str, message: str, **extra) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.extra = extra


def _unauthorized(message: str) -> MailApiError:
    return MailApiError(status.HTTP_401_UNAUTHORIZED, "UNAUTHORIZED", message)


def _not_connected(user_id: str) -> MailApiError:
    url, _state = gmail_service.authorization_url(
        user_id, redirect_uri=_effective_redirect_uri()
    )
    return MailApiError(
        status.HTTP_409_CONFLICT,
        "GMAIL_NOT_CONNECTED",
        "Gmail is not connected. Authorize it first.",
        auth_url=url,
    )


def _map_gmail_error(exc: Exception) -> MailApiError:
    if isinstance(exc, gmail_service.GmailNotConnectedError):
        # 409 需要知道是哪个用户未授权，才能给出绑定该用户的 auth_url；
        # GmailNotConnectedError 携带发起请求的 user_id。
        user_id = getattr(exc, "user_id", None)
        if user_id:
            return _not_connected(user_id)
        return MailApiError(
            status.HTTP_409_CONFLICT,
            "GMAIL_NOT_CONNECTED",
            "Gmail is not connected. Authorize it first.",
        )
    upstream_status = getattr(exc, "status_code", None)
    reason = getattr(exc, "reason", None) or str(exc)
    if isinstance(upstream_status, int) and upstream_status:
        return MailApiError(upstream_status, "GMAIL_ERROR", reason)
    return MailApiError(
        status.HTTP_502_BAD_GATEWAY,
        "GMAIL_ERROR",
        f"Gmail request failed: {reason}",
    )


def _user_from_auth(authorization: str | None) -> str:
    """Verify the caller identity and return its user id (email for web users).

    Raises:
        MailApiError: 401 when the bearer is missing, malformed, or fails
        verification against both the user-JWT key and the internal key.
    """
    try:
        return auth.resolve_identity(authorization)
    except auth.UnauthorizedError as exc:
        raise _unauthorized(str(exc)) from exc


# Bound to every request so helpers can derive the public origin (scheme + host)
# even from error paths (e.g. the 409 auth_url) that carry no Request parameter.
_current_request: contextvars.ContextVar[Request | None] = contextvars.ContextVar(
    "mail_current_request", default=None
)


def _public_base_url(request: Request | None = None) -> str:
    """The public origin the client used to reach this service.

    Behind the nginx reverse proxy the service sees container-internal host/port,
    so trust ``X-Forwarded-Proto`` + ``Host`` (both set by nginx); when accessed
    directly (local dev, port 5000) fall back to the raw URL parts.
    """
    req = request or _current_request.get()
    if req is None:
        return "http://localhost:5000"
    scheme = (
        req.headers.get("x-forwarded-proto") or req.url.scheme
    ).split(",")[0].strip()
    host = req.headers.get("host") or req.url.netloc
    return f"{scheme}://{host}"


def _effective_redirect_uri(request: Request | None = None) -> str:
    """Redirect URI the Google OAuth flow should use.

    An explicitly configured ``GMAIL_REDIRECT_URI`` (local dev) wins; otherwise
    derive ``https://<public-host>/callback`` from the request so any deployment
    domain matches the URI registered in the Google Cloud Console.
    """
    if config.GMAIL_REDIRECT_URI:
        return config.GMAIL_REDIRECT_URI
    return f"{_public_base_url(request)}/callback"


app = FastAPI(title="CampusLink Mail Service", version="0.2.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:8080", "http://localhost:5000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def _bind_current_request(request: Request, call_next):
    token = _current_request.set(request)
    try:
        return await call_next(request)
    finally:
        _current_request.reset(token)


@app.exception_handler(MailApiError)
async def _handle_mail_api_error(request: Request, exc: MailApiError) -> JSONResponse:
    body = {"code": exc.code, "error": exc.message}
    body.update(exc.extra)
    return JSONResponse(status_code=exc.status_code, content=body)


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": "mail-agent",
        # Gmail 现在是按用户授权的：健康检查报告有多少用户已绑定 Gmail。
        "gmail_connected": gmail_service.connected_user_count() > 0,
        "gmail_users_connected": gmail_service.connected_user_count(),
        "agent_configured": agent.is_configured(),
        "agent_model": config.MAIL_LLM_MODEL,
    }


# ---------------------------------------------------------------------------
# OAuth2 (per user)
# ---------------------------------------------------------------------------

@app.get("/api/mail/oauth/url", response_model=OAuthUrlResponse)
async def oauth_url(authorization: str | None = Header(default=None)) -> OAuthUrlResponse:
    user_id = _user_from_auth(authorization)
    url, _state = gmail_service.authorization_url(
        user_id, redirect_uri=_effective_redirect_uri()
    )
    return OAuthUrlResponse(auth_url=url, connected=gmail_service.is_connected(user_id))


@app.get("/api/mail/oauth/status", response_model=OAuthStatusResponse)
async def oauth_status(authorization: str | None = Header(default=None)) -> OAuthStatusResponse:
    user_id = _user_from_auth(authorization)
    return OAuthStatusResponse(
        connected=gmail_service.is_connected(user_id),
        email=gmail_service.connected_email(user_id),
    )


@app.get("/callback")
async def oauth_callback(
    request: Request,
    code: str | None = None,
    state: str | None = None,
    error: str | None = None,
):
    if error:
        raise MailApiError(status.HTTP_400_BAD_REQUEST, "OAUTH_ERROR", error)
    if not code or not state:
        raise MailApiError(
            status.HTTP_400_BAD_REQUEST,
            "OAUTH_ERROR",
            "Missing code or state",
        )
    try:
        gmail_service.exchange_code(code, state)
    except ValueError as exc:
        raise MailApiError(status.HTTP_400_BAD_REQUEST, "OAUTH_ERROR", str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc
    frontend = config.FRONTEND_URL or _public_base_url(request)
    return RedirectResponse(url=f"{frontend}/mail?connected=1")


@app.post("/api/mail/oauth/disconnect", response_model=OAuthStatusResponse)
async def oauth_disconnect(authorization: str | None = Header(default=None)) -> OAuthStatusResponse:
    user_id = _user_from_auth(authorization)
    gmail_service.reset_connection(user_id)
    return OAuthStatusResponse(connected=False, email=None)


# ---------------------------------------------------------------------------
# Mail operations
# ---------------------------------------------------------------------------

@app.get("/api/mail/messages", response_model=PageResponse)
def list_messages(
    authorization: str | None = Header(default=None),
    folder: MailFolder = MailFolder.inbox,
    q: str = "",
    unread: bool | None = None,
    starred: bool | None = None,
    page: int = Query(default=0, ge=0),
    size: int = Query(default=20, ge=1, le=gmail_service.MAX_PAGE_SIZE),
) -> PageResponse:
    user_id = _user_from_auth(authorization)
    try:
        messages, total, has_next = gmail_service.list_messages(
            user_id, folder, q, unread, starred, page, size
        )
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc
    total_pages = max(1, math.ceil(total / size)) if total else 0
    return PageResponse(
        content=messages,
        page=page,
        size=size,
        total_elements=total,
        total_pages=total_pages,
        first=page == 0,
        last=not has_next or page >= total_pages - 1,
    )


@app.get("/api/mail/messages/{message_id}", response_model=MailMessage)
def get_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    user_id = _user_from_auth(authorization)
    try:
        return gmail_service.get_message(user_id, message_id, mark_read=True)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.post(
    "/api/mail/messages",
    response_model=MailMessage,
    status_code=status.HTTP_201_CREATED,
)
def send_message(
    request: SendMailRequest,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    user_id = _user_from_auth(authorization)
    try:
        return gmail_service.send_message(user_id, request)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.patch("/api/mail/messages/{message_id}", response_model=MailMessage)
def update_message(
    message_id: str,
    request: UpdateMailRequest,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    user_id = _user_from_auth(authorization)
    try:
        return gmail_service.update_message(
            user_id, message_id, request.read, request.starred, request.folder
        )
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.post("/api/mail/messages/{message_id}/archive", response_model=MailMessage)
def archive_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    user_id = _user_from_auth(authorization)
    try:
        return gmail_service.archive_message(user_id, message_id)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.post("/api/mail/messages/{message_id}/delete", response_model=MailMessage)
def delete_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    user_id = _user_from_auth(authorization)
    try:
        return gmail_service.trash_message(user_id, message_id)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


# ---------------------------------------------------------------------------
# Calendar (per-user events + schedule extraction from mail)
# ---------------------------------------------------------------------------


class ExtractResponse(BaseModel):
    days: int
    scanned: int
    mode: str = "rules"
    events: list[ExtractedSchedule]


@app.get("/api/mail/calendar/events", response_model=list[CalendarEvent])
def list_calendar_events(
    authorization: str | None = Header(default=None),
    start: str | None = Query(default=None, description="ISO datetime, inclusive lower bound"),
    end: str | None = Query(default=None, description="ISO datetime, exclusive upper bound"),
) -> list[CalendarEvent]:
    user_id = _user_from_auth(authorization)
    return calendar_service.list_events(user_id, start, end)


@app.post(
    "/api/mail/calendar/events",
    response_model=CalendarEvent,
    status_code=status.HTTP_201_CREATED,
)
def create_calendar_event(
    request: CalendarEventRequest,
    authorization: str | None = Header(default=None),
) -> CalendarEvent:
    user_id = _user_from_auth(authorization)
    try:
        return calendar_service.create_event(user_id, request)
    except ValueError as exc:
        raise MailApiError(status.HTTP_422_UNPROCESSABLE_ENTITY, "CALENDAR_VALIDATION", str(exc)) from exc


@app.get("/api/mail/calendar/events/{event_id}", response_model=CalendarEvent)
def get_calendar_event(
    event_id: str,
    authorization: str | None = Header(default=None),
) -> CalendarEvent:
    user_id = _user_from_auth(authorization)
    event = calendar_service.get_event(user_id, event_id)
    if event is None:
        raise MailApiError(status.HTTP_404_NOT_FOUND, "CALENDAR_EVENT_NOT_FOUND", "Calendar event not found")
    return event


@app.patch("/api/mail/calendar/events/{event_id}", response_model=CalendarEvent)
def update_calendar_event(
    event_id: str,
    request: CalendarEventUpdate,
    authorization: str | None = Header(default=None),
) -> CalendarEvent:
    user_id = _user_from_auth(authorization)
    try:
        event = calendar_service.update_event(user_id, event_id, request)
    except ValueError as exc:
        raise MailApiError(status.HTTP_422_UNPROCESSABLE_ENTITY, "CALENDAR_VALIDATION", str(exc)) from exc
    if event is None:
        raise MailApiError(status.HTTP_404_NOT_FOUND, "CALENDAR_EVENT_NOT_FOUND", "Calendar event not found")
    return event


@app.delete("/api/mail/calendar/events/{event_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_calendar_event(
    event_id: str,
    authorization: str | None = Header(default=None),
) -> None:
    user_id = _user_from_auth(authorization)
    if not calendar_service.delete_event(user_id, event_id):
        raise MailApiError(status.HTTP_404_NOT_FOUND, "CALENDAR_EVENT_NOT_FOUND", "Calendar event not found")


@app.post("/api/mail/calendar/extract", response_model=ExtractResponse)
def extract_calendar_schedules(
    authorization: str | None = Header(default=None),
    days: int = Query(default=0, ge=0, le=30, description="Scan emails from the last `days` days (0 = today only)"),
    max_results: int = Query(default=20, ge=1, le=50),
) -> ExtractResponse:
    """Scan recent emails for date/time mentions and return proposed schedules.

    Nothing is written yet: the frontend shows these to the user, who confirms
    the selection, then calls ``POST /api/mail/calendar/import``.
    """
    user_id = _user_from_auth(authorization)
    try:
        messages = gmail_service.list_recent_messages(user_id, days, max_results)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc
    events, mode = calendar_service.extract_schedules_with_mode(messages)
    return ExtractResponse(days=days, scanned=len(messages), mode=mode, events=events)


@app.post("/api/mail/calendar/import", response_model=ImportResponse)
def import_calendar_schedules(
    request: ImportRequest,
    authorization: str | None = Header(default=None),
) -> ImportResponse:
    """Import user-confirmed schedules extracted from mail into the calendar."""
    user_id = _user_from_auth(authorization)
    return calendar_service.import_schedules(user_id, request.events)


# ---------------------------------------------------------------------------
# Mail agent (LangChain)
# ---------------------------------------------------------------------------


class MailAgentChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=8000)
    session_id: str = Field(default="", max_length=256, description="Multi-turn chat session id")


class MailAgentAction(BaseModel):
    tool: str
    args: dict[str, object] = Field(default_factory=dict)


class MailAgentChatResponse(BaseModel):
    response: str
    status: str = "completed"
    session_id: str
    actions_taken: list[MailAgentAction] = Field(default_factory=list)
    model: str = ""


def _sanitize_session_id(raw: str) -> str:
    """Sanitize the session id before it becomes a LangGraph thread key."""
    if not raw:
        return ""
    return re.sub(r"[^A-Za-z0-9_-]", "", raw)[:128]


@app.post("/api/mail/agent/chat", response_model=MailAgentChatResponse)
async def agent_chat(
    request: MailAgentChatRequest,
    authorization: str | None = Header(default=None),
) -> MailAgentChatResponse:
    """Run a natural-language request through the LangChain mail agent.

    The agent can search, read, delete, star, archive and send mail via the
    Gmail-backed ``gmail_service``, always acting on the *requesting user's own*
    mailbox. Reuse the same ``session_id`` to keep multi-turn conversation
    context.
    """
    user_id = _user_from_auth(authorization)
    if not agent.is_configured():
        raise MailApiError(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "MAIL_AGENT_NOT_CONFIGURED",
            "Mail agent is not configured. Set MAIL_LLM_API_KEY (or DEEPSEEK_API_KEY) in .env.",
        )
    session_id = _sanitize_session_id(request.session_id) or f"anon-{uuid.uuid4().hex}"
    try:
        result = await agent.run_chat(request.message, session_id, user_id)
    except Exception as exc:  # noqa: BLE001
        raise MailApiError(
            status.HTTP_500_INTERNAL_SERVER_ERROR,
            "MAIL_AGENT_ERROR",
            f"Mail agent failed: {exc}",
        ) from exc
    return MailAgentChatResponse(
        response=result["response"],
        status="completed",
        session_id=result["session_id"],
        actions_taken=[
            MailAgentAction(tool=action["tool"], args=action.get("args") or {})
            for action in result["actions_taken"]
        ],
        model=result["model"],
    )
